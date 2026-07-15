package com.kicksidepanel.kick;

import java.awt.Color;

/**
 * A single parsed Kick chat message, ready for display. No emote/badge image rendering by
 * design - see the plugin's README for why.
 */
public class KickMessage
{
	public final String username;
	public final String body;
	public final Color color;
	public final long receivedAtMillis;

	public KickMessage(String username, String body, Color color, long receivedAtMillis)
	{
		this.username = username;
		this.body = body;
		this.color = color;
		this.receivedAtMillis = receivedAtMillis;
	}
}
