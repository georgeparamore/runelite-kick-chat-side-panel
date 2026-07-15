package com.kicksidepanel.kick;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class KickChannelNameTest
{
	@Test
	public void plainNameIsUnchanged()
	{
		assertEquals("qualify333", KickChannelName.normalize("qualify333"));
	}

	@Test
	public void trimsWhitespace()
	{
		assertEquals("qualify333", KickChannelName.normalize("  qualify333  "));
	}

	@Test
	public void lowercasesMixedCase()
	{
		assertEquals("qualify333", KickChannelName.normalize("Qualify333"));
	}

	@Test
	public void extractsSlugFromPlainUrl()
	{
		assertEquals("qualify333", KickChannelName.normalize("https://kick.com/qualify333"));
	}

	@Test
	public void extractsSlugFromUrlWithoutProtocol()
	{
		assertEquals("qualify333", KickChannelName.normalize("kick.com/qualify333"));
	}

	@Test
	public void extractsSlugFromUrlWithTrailingPath()
	{
		assertEquals("qualify333", KickChannelName.normalize("https://kick.com/qualify333/videos"));
	}

	@Test
	public void extractsSlugFromUrlWithQueryString()
	{
		assertEquals("qualify333", KickChannelName.normalize("https://kick.com/qualify333?foo=bar"));
	}

	@Test
	public void stripsLeadingAt()
	{
		assertEquals("qualify333", KickChannelName.normalize("@qualify333"));
	}

	@Test
	public void emptyAndNullBecomeEmptyString()
	{
		assertEquals("", KickChannelName.normalize(""));
		assertEquals("", KickChannelName.normalize(null));
		assertEquals("", KickChannelName.normalize("   "));
	}
}
