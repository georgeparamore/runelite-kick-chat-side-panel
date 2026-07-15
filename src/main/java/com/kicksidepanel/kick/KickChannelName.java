package com.kicksidepanel.kick;

import java.util.Locale;

/**
 * Normalizes whatever gets typed or pasted into the "Kick channel" config field into a plain
 * channel slug - so pasting a full channel URL straight from a browser's address bar (e.g.
 * {@code https://kick.com/somechannel}) works exactly the same as typing the slug itself.
 */
public final class KickChannelName
{
	private KickChannelName()
	{
	}

	public static String normalize(String raw)
	{
		if (raw == null)
		{
			return "";
		}

		String value = raw.trim();
		if (value.isEmpty())
		{
			return "";
		}

		while (value.startsWith("@"))
		{
			value = value.substring(1);
		}

		int kickTv = value.toLowerCase(Locale.ROOT).indexOf("kick.com/");
		if (kickTv >= 0)
		{
			value = value.substring(kickTv + "kick.com/".length());
		}

		// Drop any path/query/fragment after the channel slug itself, e.g. the "/videos" in
		// ".../somechannel/videos" or a "?foo=bar" some share links add.
		value = value.split("[/?#]", 2)[0];

		return value.trim().toLowerCase(Locale.ROOT);
	}
}
