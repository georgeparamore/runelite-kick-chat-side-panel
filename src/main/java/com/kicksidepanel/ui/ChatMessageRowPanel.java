package com.kicksidepanel.ui;

import com.kicksidepanel.kick.KickMessage;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * A single chat line, flowing as one continuous wrapped paragraph - "Username: message text"
 * all in one JTextPane. Plain text only, deliberately: no emote or badge image rendering (see
 * the plugin README for why). A message that mentions {@code myUsername} is highlighted, and
 * clicking the sender's name starts a reply to that person via {@code onUsernameClicked}.
 */
public class ChatMessageRowPanel extends JPanel
{
	private static final Color DEFAULT_NAME_COLOR = new Color(0x53, 0xfc, 0x18);
	private static final Color BODY_COLOR = new Color(0xef, 0xef, 0xf1);
	private static final Color TIME_COLOR = new Color(0x6d, 0x6d, 0x78);
	private static final Color MENTION_BACKGROUND = new Color(0x1d, 0x3a, 0x14);
	private static final Color MENTION_BORDER = new Color(0x53, 0xfc, 0x18);
	private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm");

	public ChatMessageRowPanel(KickMessage message, boolean colorUsernames, boolean showTimestamp,
		float fontSize, String myUsername, Consumer<String> onUsernameClicked)
	{
		setLayout(new BorderLayout());
		setAlignmentX(LEFT_ALIGNMENT);

		if (mentionsUser(message.body, myUsername))
		{
			setOpaque(true);
			setBackground(MENTION_BACKGROUND);
			setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 3, 0, 0, MENTION_BORDER),
				BorderFactory.createEmptyBorder(2, 7, 2, 10)));
		}
		else
		{
			setOpaque(false);
			setBorder(BorderFactory.createEmptyBorder(2, 10, 2, 10));
		}

		JTextPane pane = buildLine(message, colorUsernames, showTimestamp, fontSize, onUsernameClicked);
		add(pane, BorderLayout.CENTER);
	}

	/**
	 * A plain JPanel inside a BoxLayout column defaults to an unbounded maximum height, so
	 * with only a few short messages in a tall scroll panel, BoxLayout was distributing all
	 * the empty leftover vertical space into the rows themselves instead of leaving it blank
	 * at the bottom. Tying the maximum height to the current preferred height stops that.
	 */
	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}

	/**
	 * True if {@code body} mentions {@code username} as a whole word, with or without a
	 * leading "@".
	 */
	private static boolean mentionsUser(String body, String username)
	{
		if (username == null || username.isEmpty())
		{
			return false;
		}
		String pattern = "(?i)(?<![A-Za-z0-9_])@?" + Pattern.quote(username) + "(?![A-Za-z0-9_])";
		return Pattern.compile(pattern).matcher(body).find();
	}

	private JTextPane buildLine(KickMessage message, boolean colorUsernames, boolean showTimestamp,
		float fontSize, Consumer<String> onUsernameClicked)
	{
		JTextPane pane = new JTextPane();
		pane.setEditable(false);
		pane.setOpaque(false);
		pane.setBorder(null);
		pane.setFont(pane.getFont().deriveFont(fontSize));

		StyledDocument doc = pane.getStyledDocument();

		SimpleAttributeSet timeStyle = new SimpleAttributeSet();
		StyleConstants.setForeground(timeStyle, TIME_COLOR);

		SimpleAttributeSet nameStyle = new SimpleAttributeSet();
		StyleConstants.setForeground(nameStyle, colorUsernames && message.color != null ? message.color : DEFAULT_NAME_COLOR);
		StyleConstants.setBold(nameStyle, true);

		SimpleAttributeSet bodyStyle = new SimpleAttributeSet();
		StyleConstants.setForeground(bodyStyle, BODY_COLOR);

		int nameStart = -1;
		int nameEnd = -1;

		try
		{
			if (showTimestamp)
			{
				doc.insertString(doc.getLength(), TIME_FORMAT.format(new Date(message.receivedAtMillis)) + "  ", timeStyle);
			}

			nameStart = doc.getLength();
			doc.insertString(doc.getLength(), message.username + ": ", nameStyle);
			nameEnd = nameStart + message.username.length();

			doc.insertString(doc.getLength(), message.body, bodyStyle);
		}
		catch (BadLocationException e)
		{
			// Only possible if our own offset bookkeeping above is wrong; fall back to the
			// raw, un-styled message rather than showing nothing.
			pane.setText(message.username + ": " + message.body);
		}

		addUsernameClickHandling(pane, nameStart, nameEnd, message.username, onUsernameClicked);

		return pane;
	}

	/**
	 * Lets clicking the sender's name (the "Username" in "Username: message") start a reply
	 * to them, the same click-a-name-to-@mention gesture Kick's own chat offers.
	 */
	private void addUsernameClickHandling(JTextPane pane, int nameStart, int nameEnd, String username,
		Consumer<String> onUsernameClicked)
	{
		if (nameStart < 0)
		{
			return;
		}

		pane.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (isOverName(pane, e, nameStart, nameEnd))
				{
					onUsernameClicked.accept(username);
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				pane.setCursor(Cursor.getDefaultCursor());
			}
		});

		pane.addMouseMotionListener(new MouseMotionAdapter()
		{
			@Override
			public void mouseMoved(MouseEvent e)
			{
				pane.setCursor(Cursor.getPredefinedCursor(
					isOverName(pane, e, nameStart, nameEnd) ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
			}
		});
	}

	private static boolean isOverName(JTextPane pane, MouseEvent e, int nameStart, int nameEnd)
	{
		int offset = pane.viewToModel2D(e.getPoint());
		return offset >= nameStart && offset < nameEnd;
	}
}
