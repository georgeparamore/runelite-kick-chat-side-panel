package com.kicksidepanel.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Rectangle;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Watches a text field for an in-progress "@partialname" and pops up a filtered list of
 * recently-seen chatters to complete it from - the same "@" mention autocomplete Kick's own
 * chat input offers. Wires itself to the field entirely through listeners; nothing else needs
 * to hold a reference to this once constructed.
 */
final class MentionAutocomplete
{
	private static final Color BACKGROUND = new Color(0x17, 0x17, 0x1d);
	private static final Color BORDER = new Color(0x33, 0x33, 0x3d);
	private static final Color SELECTED = new Color(0x2f, 0xa8, 0x0e);
	private static final int MAX_SUGGESTIONS = 8;
	private static final int ROW_HEIGHT = 22;

	private final JTextField field;
	private final Supplier<List<String>> recentUsernamesSupplier;
	private final JPopupMenu popup = new JPopupMenu();
	private final DefaultListModel<String> listModel = new DefaultListModel<>();
	private final JList<String> list = new JList<>(listModel);

	private int mentionStart = -1;

	MentionAutocomplete(JTextField field, Supplier<List<String>> recentUsernamesSupplier)
	{
		this.field = field;
		this.recentUsernamesSupplier = recentUsernamesSupplier;

		list.setBackground(BACKGROUND);
		list.setForeground(Color.WHITE);
		list.setSelectionBackground(SELECTED);
		list.setSelectionForeground(Color.WHITE);
		list.setFont(list.getFont().deriveFont(12f));
		list.setFixedCellHeight(ROW_HEIGHT);
		list.setCellRenderer(new DefaultListCellRenderer()
		{
			@Override
			public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean isSelected,
				boolean cellHasFocus)
			{
				super.getListCellRendererComponent(l, "@" + value, index, isSelected, cellHasFocus);
				setOpaque(true);
				setBackground(isSelected ? SELECTED : BACKGROUND);
				setForeground(Color.WHITE);
				setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
				return this;
			}
		});
		list.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				int index = list.locationToIndex(e.getPoint());
				if (index >= 0)
				{
					applySelection(index);
				}
			}
		});

		JScrollPane scrollPane = new JScrollPane(list);
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

		popup.setBorder(BorderFactory.createLineBorder(BORDER));
		popup.setBackground(BACKGROUND);
		popup.add(scrollPane);
		popup.setFocusable(false);

		field.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				SwingUtilities.invokeLater(MentionAutocomplete.this::onTextChanged);
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				SwingUtilities.invokeLater(MentionAutocomplete.this::onTextChanged);
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
			}
		});

		field.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyPressed(KeyEvent e)
			{
				if (!popup.isVisible())
				{
					return;
				}
				switch (e.getKeyCode())
				{
					case KeyEvent.VK_DOWN:
						moveSelection(1);
						e.consume();
						break;
					case KeyEvent.VK_UP:
						moveSelection(-1);
						e.consume();
						break;
					case KeyEvent.VK_ENTER:
					case KeyEvent.VK_TAB:
						if (list.getSelectedIndex() >= 0)
						{
							applySelection(list.getSelectedIndex());
							e.consume();
						}
						break;
					case KeyEvent.VK_ESCAPE:
						hide();
						e.consume();
						break;
					default:
						break;
				}
			}
		});
	}

	private void moveSelection(int delta)
	{
		int size = listModel.size();
		if (size == 0)
		{
			return;
		}
		int next = Math.max(0, Math.min(size - 1, list.getSelectedIndex() + delta));
		list.setSelectedIndex(next);
		list.ensureIndexIsVisible(next);
	}

	private void onTextChanged()
	{
		String text = field.getText();
		int caret = field.getCaretPosition();
		int at = findMentionStart(text, caret);
		if (at < 0)
		{
			hide();
			return;
		}

		String query = text.substring(at + 1, caret);
		List<String> matches = new ArrayList<>();
		for (String name : recentUsernamesSupplier.get())
		{
			if (name.regionMatches(true, 0, query, 0, query.length()))
			{
				matches.add(name);
				if (matches.size() >= MAX_SUGGESTIONS)
				{
					break;
				}
			}
		}

		if (matches.isEmpty())
		{
			hide();
			return;
		}

		mentionStart = at;
		listModel.clear();
		for (String match : matches)
		{
			listModel.addElement(match);
		}
		list.setSelectedIndex(0);
		list.setVisibleRowCount(matches.size());

		if (!popup.isVisible())
		{
			showBelowCaret(caret);
		}
	}

	private void showBelowCaret(int caret)
	{
		try
		{
			Rectangle caretBounds = field.modelToView2D(caret).getBounds();
			int height = list.getVisibleRowCount() * ROW_HEIGHT + 4;
			popup.show(field, caretBounds.x, -height - 4);
		}
		catch (Exception ignored)
		{
			// Falls back to just not showing rather than crashing on a caret-geometry
			// edge case (e.g. the field isn't showing yet).
		}
	}

	private static int findMentionStart(String text, int caret)
	{
		for (int i = caret - 1; i >= 0; i--)
		{
			char c = text.charAt(i);
			if (c == '@')
			{
				return i;
			}
			if (Character.isWhitespace(c))
			{
				return -1;
			}
		}
		return -1;
	}

	private void applySelection(int index)
	{
		String username = listModel.get(index);
		String text = field.getText();
		int caret = field.getCaretPosition();
		String before = text.substring(0, mentionStart);
		String after = caret <= text.length() ? text.substring(caret) : "";
		String replacement = "@" + username + " ";

		hide();
		field.setText(before + replacement + after);
		field.setCaretPosition(before.length() + replacement.length());
		field.requestFocusInWindow();
	}

	private void hide()
	{
		mentionStart = -1;
		if (popup.isVisible())
		{
			popup.setVisible(false);
		}
	}
}
