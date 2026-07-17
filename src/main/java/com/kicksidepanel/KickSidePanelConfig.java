package com.kicksidepanel;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("kicksidepanel")
public interface KickSidePanelConfig extends Config
{
	@ConfigItem(
		keyName = "channel",
		name = "Kick channel",
		description = "Enter the Kick channel name or URL you want to connect to.",
		position = 1
	)
	default String channel()
	{
		return "";
	}

	@ConfigItem(
		keyName = "autoConnect",
		name = "Auto-connect on login",
		description = "Connect automatically when the client starts.",
		position = 2
	)
	default boolean autoConnect()
	{
		return false;
	}

	@ConfigItem(
		keyName = "colorUsernames",
		name = "Color usernames",
		description = "Show each chatter's name in their Kick color.",
		position = 3
	)
	default boolean colorUsernames()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showTimestamps",
		name = "Show timestamps",
		description = "Show the time each message arrived.",
		position = 4
	)
	default boolean showTimestamps()
	{
		return true;
	}

	@ConfigItem(
		keyName = "maxMessages",
		name = "Message history",
		description = "How many messages to keep before older ones scroll off.",
		position = 5
	)
	default int maxMessages()
	{
		return 200;
	}

	@ConfigItem(
		keyName = "textSize",
		name = "Text size",
		description = "Size of incoming chat message text.",
		position = 6
	)
	default TextSize textSize()
	{
		return TextSize.MEDIUM;
	}

	enum TextSize
	{
		SMALL,
		MEDIUM,
		LARGE;

		public float points()
		{
			switch (this)
			{
				case SMALL:
					return 12f;
				case LARGE:
					return 16f;
				default:
					return 14f;
			}
		}

		@Override
		public String toString()
		{
			switch (this)
			{
				case SMALL:
					return "Small";
				case LARGE:
					return "Large";
				default:
					return "Medium";
			}
		}
	}

	// --- Internal state below, not shown in the settings UI. Written directly via
	// ConfigManager.setConfiguration() rather than through this interface. ---

	@ConfigItem(keyName = "accessToken", name = "", description = "", hidden = true)
	default String accessToken()
	{
		return "";
	}

	@ConfigItem(keyName = "refreshToken", name = "", description = "", hidden = true)
	default String refreshToken()
	{
		return "";
	}

	@ConfigItem(keyName = "loggedInUsername", name = "", description = "", hidden = true)
	default String loggedInUsername()
	{
		return "";
	}
}
