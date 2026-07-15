package com.kicksidepanel;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Launches the real RuneLite client locally with KickSidePanelPlugin registered, so it can be
 * tested without going through the Plugin Hub. Run this class's main method (e.g. "Run" in
 * your IDE); log in with your own account, then enable "Kick Chat Side Panel" in the plugin
 * list.
 */
public class KickSidePanelPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(KickSidePanelPlugin.class);
		RuneLite.main(args);
	}
}
