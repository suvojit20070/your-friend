package com.suvo.agent.ui;

/** A row in the chat UI. */
public class ChatMsg {
    public static final int USER = 0, AURA = 1, TOOL = 2;
    public int type;
    public String text;

    public ChatMsg(int type, String text) {
        this.type = type;
        this.text = text;
    }
}
