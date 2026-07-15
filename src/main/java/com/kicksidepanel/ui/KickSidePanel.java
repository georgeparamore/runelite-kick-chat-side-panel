package com.kicksidepanel.ui;

import com.kicksidepanel.kick.KickMessage;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.PluginPanel;

/**
 * Side panel that shows Kick chat as a scrolling message feed, styled to match Kick's own
 * chat window as closely as a Swing side panel reasonably can, Party-Hub style. Only ever
 * connects to the single channel configured in the plugin's settings - there is no free-text
 * field or other way to switch to a different channel from the panel itself.
 * <p>
 * Reading chat works with no login. Logging in with Kick (OAuth Authorization Code + PKCE)
 * additionally unlocks sending messages.
 */
public class KickSidePanel extends PluginPanel
{
	public interface Handlers
	{
		void onConnectClicked();

		void onDisconnectClicked();

		void onLoginClicked();

		void onCancelLoginClicked();

		void onLogoutClicked();

		void onSendMessage(String text);
	}

	private enum AuthState
	{
		LOGGED_OUT,
		WAITING_FOR_LOGIN,
		LOGGED_IN
	}

	private static final Color BACKGROUND = new Color(0x0e, 0x0e, 0x12);
	private static final Color TAB_BAR_BACKGROUND = new Color(0x17, 0x17, 0x1d);
	private static final Color DIVIDER = new Color(0x26, 0x26, 0x2e);
	private static final Color MUTED_TEXT = new Color(0x8a, 0x8a, 0x95);
	private static final Color ACCENT = new Color(0x53, 0xfc, 0x18);
	private static final Color ACCENT_HOVER = new Color(0x74, 0xfd, 0x47);
	private static final Color ACCENT_TEXT = Color.BLACK;

	private final JLabel channelLabel;
	private final OpenChannelButton openChannelButton;
	private final PillButton connectButton;
	private final JLabel statusLabel;
	private final JLabel authStatusLabel;
	private final PillButton authButton;
	private final JPanel messageListPanel;
	private final JScrollPane scrollPane;
	private final PlaceholderTextField messageField;
	private final PillButton sendButton;
	private final JPanel sendRow;

	private final Handlers handlers;
	private boolean connected;
	private boolean channelConfigured;
	private AuthState authState = AuthState.LOGGED_OUT;
	private volatile String myUsername;
	private volatile String currentChannel = "";

	private static final int MAX_RECENT_USERNAMES = 300;
	private final List<String> recentUsernames = new ArrayList<>();

	public KickSidePanel(Handlers handlers, String channel)
	{
		// PluginPanel's default (no-arg) constructor wraps all content in its own
		// DynamicGridLayout + JScrollPane machinery, which conflicts with our own
		// BorderLayout + JScrollPane below. super(false) opts out of that.
		super(false);

		this.handlers = handlers;

		setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
		setBackground(BACKGROUND);
		setLayout(new BorderLayout());

		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setBackground(BACKGROUND);
		header.setBorder(BorderFactory.createEmptyBorder(10, 10, 8, 10));

		JLabel title = new JLabel("Kick Chat");
		title.setForeground(Color.WHITE);
		title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
		title.setAlignmentX(LEFT_ALIGNMENT);

		JPanel connectRow = new JPanel(new BorderLayout(6, 0));
		connectRow.setBackground(BACKGROUND);
		connectRow.setAlignmentX(LEFT_ALIGNMENT);
		connectRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
		connectRow.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

		channelLabel = new JLabel();
		channelLabel.setForeground(MUTED_TEXT);

		openChannelButton = new OpenChannelButton(BACKGROUND);
		openChannelButton.addActionListener(e -> openChannelInBrowser());

		JPanel channelNameRow = new JPanel(new BorderLayout(2, 0));
		channelNameRow.setOpaque(false);
		channelNameRow.add(channelLabel, BorderLayout.CENTER);
		channelNameRow.add(openChannelButton, BorderLayout.EAST);

		connectButton = new PillButton("Connect", ACCENT, ACCENT_HOVER);
		connectButton.setForeground(ACCENT_TEXT);
		connectButton.addActionListener(e -> handleConnectButton());

		connectRow.add(channelNameRow, BorderLayout.CENTER);
		connectRow.add(connectButton, BorderLayout.EAST);

		statusLabel = new JLabel("Not connected");
		statusLabel.setForeground(MUTED_TEXT);
		statusLabel.setFont(statusLabel.getFont().deriveFont(11f));
		statusLabel.setAlignmentX(LEFT_ALIGNMENT);
		statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));

		JPanel authRow = new JPanel(new BorderLayout(6, 0));
		authRow.setBackground(BACKGROUND);
		authRow.setAlignmentX(LEFT_ALIGNMENT);
		authRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
		authRow.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

		authStatusLabel = new JLabel("Not logged in");
		authStatusLabel.setForeground(MUTED_TEXT);
		authStatusLabel.setFont(authStatusLabel.getFont().deriveFont(11f));

		authButton = new PillButton("Log in with Kick", new Color(0x2f, 0xa8, 0x0e), new Color(0x3c, 0xc2, 0x18));
		authButton.addActionListener(e -> handleAuthButton());

		authRow.add(authStatusLabel, BorderLayout.CENTER);
		authRow.add(authButton, BorderLayout.EAST);

		header.add(title);
		header.add(connectRow);
		header.add(statusLabel);
		header.add(authRow);

		JPanel tabBar = new JPanel(new BorderLayout());
		tabBar.setBackground(TAB_BAR_BACKGROUND);
		tabBar.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 1, 0, DIVIDER),
			BorderFactory.createEmptyBorder(6, 0, 6, 0)));
		JLabel tabLabel = new JLabel("STREAM CHAT", SwingConstants.CENTER);
		tabLabel.setForeground(MUTED_TEXT);
		tabLabel.setFont(tabLabel.getFont().deriveFont(Font.BOLD, 11f));
		tabBar.add(tabLabel, BorderLayout.CENTER);

		JPanel topContainer = new JPanel();
		topContainer.setLayout(new BoxLayout(topContainer, BoxLayout.Y_AXIS));
		topContainer.setBackground(BACKGROUND);
		topContainer.add(header);
		topContainer.add(tabBar);

		messageListPanel = new ScrollableMessagePanel();
		messageListPanel.setLayout(new BoxLayout(messageListPanel, BoxLayout.Y_AXIS));
		messageListPanel.setBackground(BACKGROUND);
		messageListPanel.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
		messageListPanel.add(Box.createVerticalGlue());

		scrollPane = new JScrollPane(messageListPanel);
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.getViewport().setBackground(BACKGROUND);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);

		sendRow = new JPanel(new BorderLayout(8, 0));
		sendRow.setBackground(BACKGROUND);
		sendRow.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, DIVIDER),
			BorderFactory.createEmptyBorder(8, 10, 10, 10)));
		sendRow.setVisible(false);

		Color fieldBackground = new Color(0x1e, 0x1e, 0x26);

		messageField = new PlaceholderTextField("Message");
		messageField.setBackground(fieldBackground);
		messageField.setForeground(Color.WHITE);
		messageField.setCaretColor(Color.WHITE);
		messageField.setBorder(BorderFactory.createEmptyBorder());
		messageField.setMargin(new java.awt.Insets(6, 10, 6, 10));
		messageField.addActionListener(e -> handleSend());
		new MentionAutocomplete(messageField, () -> recentUsernames);

		sendButton = new PillButton("Chat", ACCENT, ACCENT_HOVER);
		sendButton.setForeground(ACCENT_TEXT);
		sendButton.addActionListener(e -> handleSend());

		sendRow.add(messageField, BorderLayout.CENTER);
		sendRow.add(sendButton, BorderLayout.EAST);

		add(topContainer, BorderLayout.NORTH);
		add(scrollPane, BorderLayout.CENTER);
		add(sendRow, BorderLayout.SOUTH);

		setChannel(channel);
		setConnected(false);
	}

	private void handleConnectButton()
	{
		if (connected)
		{
			handlers.onDisconnectClicked();
		}
		else if (channelConfigured)
		{
			handlers.onConnectClicked();
		}
	}

	private void handleAuthButton()
	{
		switch (authState)
		{
			case LOGGED_OUT:
				handlers.onLoginClicked();
				break;
			case WAITING_FOR_LOGIN:
				handlers.onCancelLoginClicked();
				showLoginPrompt();
				break;
			case LOGGED_IN:
				handlers.onLogoutClicked();
				break;
		}
	}

	private void handleSend()
	{
		String text = messageField.getText();
		if (text != null && !text.trim().isEmpty())
		{
			handlers.onSendMessage(text);
			messageField.setText("");
		}
	}

	/**
	 * Updates the channel this panel will connect to (read from plugin config - there is no
	 * in-panel way to type a different one). Safe to call again later if the user edits the
	 * config while the panel is open.
	 */
	public void setChannel(String channel)
	{
		SwingUtilities.invokeLater(() ->
		{
			currentChannel = channel == null ? "" : channel.trim();
			channelConfigured = !currentChannel.isEmpty();
			channelLabel.setText(channelConfigured ? currentChannel : "No channel set");
			connectButton.setEnabled(channelConfigured || connected);
			openChannelButton.setEnabled(channelConfigured);
			if (!channelConfigured)
			{
				setStatus("Set your channel in the plugin settings", true);
			}
		});
	}

	private void openChannelInBrowser()
	{
		String channel = currentChannel;
		if (channel.isEmpty())
		{
			return;
		}

		try
		{
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
			{
				Desktop.getDesktop().browse(URI.create("https://kick.com/" + channel));
			}
		}
		catch (Exception ignored)
		{
			// Best-effort - nothing else to do if the platform can't open a browser.
		}
	}

	public void setConnected(boolean connected)
	{
		SwingUtilities.invokeLater(() ->
		{
			this.connected = connected;
			connectButton.setText(connected ? "Disconnect" : "Connect");
			connectButton.setEnabled(connected || channelConfigured);
			updateSendRowEnabled();
		});
	}

	public void setStatus(String text, boolean isError)
	{
		SwingUtilities.invokeLater(() ->
		{
			statusLabel.setText(text);
			statusLabel.setForeground(isError ? new Color(0xff, 0x6b, 0x6b) : MUTED_TEXT);
		});
	}

	/** Not logged in, no login attempt in progress. */
	public void showLoginPrompt()
	{
		SwingUtilities.invokeLater(() ->
		{
			authState = AuthState.LOGGED_OUT;
			authStatusLabel.setForeground(MUTED_TEXT);
			authStatusLabel.setText("Not logged in");
			authButton.setText("Log in with Kick");
			sendRow.setVisible(false);
			myUsername = null;
		});
	}

	/** Waiting for the user to approve the login in their browser. */
	public void showWaitingForLogin()
	{
		SwingUtilities.invokeLater(() ->
		{
			authState = AuthState.WAITING_FOR_LOGIN;
			authStatusLabel.setForeground(MUTED_TEXT);
			authStatusLabel.setText("Approve the login in your browser...");
			authButton.setText("Cancel");
		});
	}

	public void showLoginError(String message)
	{
		SwingUtilities.invokeLater(() ->
		{
			authState = AuthState.LOGGED_OUT;
			authStatusLabel.setForeground(new Color(0xff, 0x6b, 0x6b));
			authStatusLabel.setText(message);
			authButton.setText("Log in with Kick");
			sendRow.setVisible(false);
			myUsername = null;
		});
	}

	public void showLoggedIn(String username)
	{
		SwingUtilities.invokeLater(() ->
		{
			authState = AuthState.LOGGED_IN;
			authStatusLabel.setForeground(MUTED_TEXT);
			authStatusLabel.setText("Logged in as " + username);
			authButton.setText("Log out");
			myUsername = username;
			updateSendRowEnabled();
		});
	}

	private void updateSendRowEnabled()
	{
		sendRow.setVisible(authState == AuthState.LOGGED_IN);
		messageField.setEnabled(connected);
		sendButton.setEnabled(connected);
	}

	public void appendMessage(KickMessage message, boolean colorUsernames, boolean showTimestamps, int maxMessages)
	{
		SwingUtilities.invokeLater(() ->
		{
			recordUsername(message.username);

			ChatMessageRowPanel row = new ChatMessageRowPanel(message, colorUsernames, showTimestamps,
				myUsername, this::startReplyTo);
			insertRow(row);

			while (messageListPanel.getComponentCount() - 1 > maxMessages)
			{
				messageListPanel.remove(0);
			}

			messageListPanel.revalidate();
			messageListPanel.repaint();
			scrollPane.validate();
			scrollPane.getVerticalScrollBar().setValue(scrollPane.getVerticalScrollBar().getMaximum());
		});
	}

	/**
	 * Appends a plugin-generated notice - e.g. "Connected to channel" - styled distinctly
	 * from a real chat message.
	 */
	public void appendSystemMessage(String text)
	{
		SwingUtilities.invokeLater(() ->
		{
			insertRow(new SystemMessageRowPanel(text));
			messageListPanel.revalidate();
			messageListPanel.repaint();
			scrollPane.validate();
			scrollPane.getVerticalScrollBar().setValue(scrollPane.getVerticalScrollBar().getMaximum());
		});
	}

	/** The trailing glue component must stay last, so insert new rows just before it. */
	private void insertRow(Component row)
	{
		messageListPanel.add(row, messageListPanel.getComponentCount() - 1);
	}

	public void clearMessages()
	{
		SwingUtilities.invokeLater(() ->
		{
			messageListPanel.removeAll();
			messageListPanel.add(Box.createVerticalGlue());
			messageListPanel.revalidate();
			messageListPanel.repaint();
		});
	}

	private void recordUsername(String username)
	{
		recentUsernames.removeIf(existing -> existing.equalsIgnoreCase(username));
		recentUsernames.add(0, username);
		if (recentUsernames.size() > MAX_RECENT_USERNAMES)
		{
			recentUsernames.remove(recentUsernames.size() - 1);
		}
	}

	private void startReplyTo(String username)
	{
		String text = "@" + username + " ";
		messageField.setText(text);
		messageField.requestFocusInWindow();
		messageField.setCaretPosition(text.length());
	}

	/**
	 * A plain JPanel doesn't implement {@link Scrollable}, so a JScrollPane's viewport never
	 * forces its width to match the viewport - it's left free to grow as wide as its widest
	 * child wants. Tracking the viewport's width here gives each row's JTextPane a real
	 * bounded width to wrap its text against.
	 */
	private static class ScrollableMessagePanel extends JPanel implements Scrollable
	{
		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return visibleRect.height;
		}
	}
}
