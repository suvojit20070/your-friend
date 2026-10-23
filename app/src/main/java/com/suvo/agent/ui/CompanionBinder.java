package com.suvo.agent.ui;

import com.suvo.agent.AppGraph;
import com.suvo.agent.character.CharacterRepo;
import com.suvo.agent.character.CharacterSpec;
import com.suvo.agent.character.CharacterView;
import com.suvo.agent.core.agent.AgentListener;
import com.suvo.agent.core.agent.TaskState;
import com.suvo.agent.core.character.CharacterAnimator;
import com.suvo.agent.data.Settings;
import com.suvo.agent.voice.SpeechController;

/**
 * Connects a CharacterView to the live app state: thinking while the agent works, speaking with
 * lip sync while audio plays, listening while the mic is open, reactions to task outcomes.
 */
public final class CompanionBinder {

    private final AppGraph g;
    private final CharacterView view;
    private boolean listening;

    private final AgentListener agent = new AgentListener() {
        @Override
        public void onStatusChanged(TaskState task) {
            refresh();
        }

        @Override
        public void onFinished(TaskState task) {
            refresh();
            if (task.status == TaskState.Status.COMPLETED) view.react(CharacterAnimator.Expression.HAPPY, 1.6f);
            else if (task.status == TaskState.Status.FAILED) view.react(CharacterAnimator.Expression.SAD, 2.2f);
        }
    };

    private final SpeechController.Listener speech = new SpeechController.Listener() {
        @Override
        public void onSpeechState(SpeechController.State state, String detail) {
            refresh();
        }

        @Override
        public void onMouth(float open, float form) {
            view.setMouth(open, form);
        }
    };

    private final CharacterRepo.Listener characters = new CharacterRepo.Listener() {
        @Override
        public void onActiveCharacterChanged(CharacterSpec spec) {
            view.post(() -> view.setCharacter(spec));
        }
    };

    public CompanionBinder(AppGraph g, CharacterView view) {
        this.g = g;
        this.view = view;
    }

    public void attach() {
        view.setCharacter(g.characters.active());
        view.setQuality(g.settings.str(Settings.ANIMATION_QUALITY, "high"), g.settings.bool(Settings.REDUCED_MOTION, false));
        g.agent.addListener(agent);
        g.speech.addListener(speech);
        g.characters.addListener(characters);
        refresh();
    }

    public void detach() {
        g.agent.removeListener(agent);
        g.speech.removeListener(speech);
        g.characters.removeListener(characters);
    }

    public void setListening(boolean on) {
        listening = on;
        refresh();
    }

    public void setMicLevel(float rmsDb) {
        // SpeechRecognizer reports roughly -2..10 dB.
        view.animator().setListenLevel(Math.max(0f, Math.min(1f, (rmsDb + 2f) / 12f)));
    }

    private void refresh() {
        CharacterAnimator.Activity a;
        if (g.speech.state() == SpeechController.State.SPEAKING) a = CharacterAnimator.Activity.SPEAKING;
        else if (listening) a = CharacterAnimator.Activity.LISTENING;
        else if (g.agent.isRunning() || g.speech.state() == SpeechController.State.SYNTHESIZING) a = CharacterAnimator.Activity.THINKING;
        else a = CharacterAnimator.Activity.IDLE;
        view.setActivity(a);
    }
}
