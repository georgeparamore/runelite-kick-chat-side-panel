package com.kicksidepanel.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * A plugin-generated notice row in the message feed - e.g. "Connected to #channel" - styled
 * distinctly from real chat messages (centered, muted, italic, no sender) so it reads as a
 * system line rather than something someone said in chat.
 */
class SystemMessageRowPanel extends JPanel
{
	private static final Color TEXT_COLOR = new Color(0x6d, 0x6d, 0x78);

	SystemMessageRowPanel(String text)
	{
		setOpaque(false);
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
		setAlignmentX(LEFT_ALIGNMENT);

		JLabel label = new JLabel(text, SwingConstants.CENTER);
		label.setForeground(TEXT_COLOR);
		label.setFont(label.getFont().deriveFont(Font.ITALIC, 11f));
		add(label, BorderLayout.CENTER);
	}

	/** Same unbounded-maximum-height fix as {@link ChatMessageRowPanel} - see its javadoc. */
	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
