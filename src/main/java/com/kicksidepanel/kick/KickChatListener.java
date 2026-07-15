package com.kicksidepanel.kick;

/**
 * Callbacks fired by {@link KickChatClient}. All callbacks arrive on the client's own
 * background thread, not the Swing EDT - implementations that touch UI must hop over via
 * SwingUtilities.invokeLater themselves.
 */
public interface KickChatListener
{
	void onConnected(String channel);

	void onDisconnected(String reason);

	void onMessage(KickMessage message);
}
