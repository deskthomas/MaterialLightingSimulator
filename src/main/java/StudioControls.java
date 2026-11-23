import javax.swing.*;
import javax.swing.plaf.basic.BasicSliderUI;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

final class StudioControls implements AutoCloseable {
    private static final Color BACKGROUND = new Color(0x101018);
    private static final Color SIDEBAR = new Color(0x151521);
    private static final Color SURFACE = new Color(0x1A1A26);
    private static final Color SELECTED = new Color(0x27243C);
    private static final Color OUTLINE = new Color(0x4C4868);
    private static final Color TEXT = new Color(0xF4F3FC);
    private static final Color MUTED = new Color(0xB7B6CC);
    private static final Color ACCENT = new Color(0x8D5CF7);

    private record ObjectChoice(int index, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final IntConsumer onObject;
    private final BiConsumer<StudioLight, StudioLighting.Setting> onLight;
    private final Consumer<ScenePreset> onScene;
    private final EnumMap<StudioLight, StudioLighting.Setting> settings = new EnumMap<>(StudioLight.class);
    private final EnumMap<StudioLight, LightRow> rows = new EnumMap<>(StudioLight.class);
    private StudioLight selectedLight = StudioLight.OVERHEAD;
    private JFrame window;
    private JComboBox<String> material;
    private JComboBox<ObjectChoice> object;
    private StudioColorWheel colorWheel;
    private JSlider intensity;
    private JLabel intensityValue;
    private JTextField colorHex;
    private boolean syncingObject;
    private boolean syncingLight;

    StudioControls(IntConsumer onObject, BiConsumer<StudioLight, StudioLighting.Setting> onLight,
                   Consumer<ScenePreset> onScene) {
        this.onObject = onObject;
        this.onLight = onLight;
        this.onScene = onScene;
    }

    void mount(SceneViewport viewport, int selected, StudioLighting lighting, Runnable closeRequested) {
        EventQueue.invokeLater(() -> {
            create(viewport, lighting, closeRequested);
            syncObject(selected);
            window.setVisible(true);
            EventQueue.invokeLater(viewport::captureCamera);
        });
    }

    void setTitle(String title) {
        EventQueue.invokeLater(() -> {
            if (window != null) {
                window.setTitle(title);
            }
        });
    }

    void selectObject(int index) {
        EventQueue.invokeLater(() -> {
            if (window != null) {
                syncObject(index);
            }
        });
    }

    private void create(SceneViewport viewport, StudioLighting lighting, Runnable closeRequested) {
        window = new JFrame("Material Lighting Simulator");
        window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                closeRequested.run();
            }
        });

        var pages = new JPanel(new CardLayout());
        pages.add(createLightsPage(lighting), "Lights");
        pages.add(createObjectsPage(), "Objects");
        pages.add(createScenesPage(), "Scenes");

        var navigation = new JPanel(new GridLayout(1, 3, 8, 0));
        navigation.setBackground(SIDEBAR);
        navigation.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        var tabs = new ButtonGroup();
        for (String tab : new String[]{"Lights", "Objects", "Scenes"}) {
            var button = new DashboardTab(tab);
            tabs.add(button);
            navigation.add(button);
            button.addActionListener(event -> ((CardLayout) pages.getLayout()).show(pages, tab));
            if (tab.equals("Lights")) {
                button.setSelected(true);
            }
        }
        viewport.setControlFocusAction(() -> {
            if (tabs.getSelection() != null) {
                for (AbstractButton button : java.util.Collections.list(tabs.getElements())) {
                    if (button.isSelected()) {
                        button.requestFocusInWindow();
                        break;
                    }
                }
            }
        });

        var dashboard = new JPanel(new BorderLayout());
        dashboard.setBackground(BACKGROUND);
        dashboard.add(navigation, BorderLayout.NORTH);
        dashboard.add(pages, BorderLayout.CENTER);
        var scroll = new JScrollPane(dashboard, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, OUTLINE));
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.setPreferredSize(new Dimension(360, 0));

        var content = new JPanel(new BorderLayout());
        content.add(viewport, BorderLayout.CENTER);
        content.add(scroll, BorderLayout.EAST);
        window.setContentPane(content);
        window.getRootPane().registerKeyboardAction(event -> viewport.captureCamera(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        window.pack();
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        window.setSize(Math.min(window.getWidth(), screen.width), Math.min(window.getHeight(), screen.height));
        window.setMinimumSize(new Dimension(Math.min(720, screen.width), Math.min(500, screen.height)));
        window.setLocationRelativeTo(null);
    }

    private JPanel createLightsPage(StudioLighting lighting) {
        var fixtures = new JPanel(new GridLayout(0, 1, 0, 13));
        fixtures.setOpaque(false);
        for (StudioLight light : StudioLight.values()) {
            StudioLighting.Setting setting = lighting.get(light);
            settings.put(light, setting);
            var row = new LightRow(light, setting.enabled());
            rows.put(light, row);
            fixtures.add(row);
        }
        fixtures.setPreferredSize(new Dimension(285, 220));

        colorHex = new JTextField(7);
        colorHex.setBackground(SURFACE);
        colorHex.setForeground(TEXT);
        colorHex.setCaretColor(TEXT);
        colorHex.setFont(new Font("Segoe UI", Font.PLAIN, 15));
        colorHex.setHorizontalAlignment(SwingConstants.CENTER);
        colorHex.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(OUTLINE),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)));
        colorHex.getAccessibleContext().setAccessibleName("Selected light hex color");
        colorHex.setToolTipText("Enter a six-digit RGB color and press Enter.");
        colorHex.addActionListener(event -> applyHexColor());
        colorWheel = new StudioColorWheel(TEXT, chosen -> {
            applyColor(chosen);
        });
        colorWheel.addActionListener(event -> {
            colorHex.requestFocusInWindow();
            colorHex.selectAll();
        });

        intensity = new JSlider(0, StudioLighting.MAX_INTENSITY_PERCENT);
        intensity.setOpaque(false);
        intensity.setPreferredSize(new Dimension(194, 30));
        intensity.setUI(new IntensitySliderUI(intensity));
        intensityValue = label("", 17, MUTED);
        intensityValue.setHorizontalAlignment(SwingConstants.RIGHT);
        var intensityLabel = label("Intensity", 17, TEXT);
        intensityLabel.setLabelFor(intensity);
        intensity.addChangeListener(event -> {
            int value = intensity.getValue();
            intensityValue.setText(value + "%");
            if (!syncingLight) {
                StudioLighting.Setting old = settings.get(selectedLight);
                updateLight(selectedLight, new StudioLighting.Setting(old.enabled(), old.rgb(), value));
            }
        });
        var sliderHeader = new JPanel(new BorderLayout());
        sliderHeader.setOpaque(false);
        sliderHeader.add(intensityLabel, BorderLayout.WEST);
        sliderHeader.add(intensityValue, BorderLayout.EAST);
        var sliderGroup = new JPanel(new BorderLayout(0, 8));
        sliderGroup.setOpaque(false);
        sliderGroup.add(sliderHeader, BorderLayout.NORTH);
        sliderGroup.add(intensity, BorderLayout.CENTER);

        var optics = new JPanel(new BorderLayout(0, 4));
        optics.setOpaque(false);
        var wheelRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        wheelRow.setOpaque(false);
        wheelRow.add(colorWheel);
        var hexRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        hexRow.setOpaque(false);
        hexRow.add(colorHex);
        optics.add(wheelRow, BorderLayout.CENTER);
        optics.add(hexRow, BorderLayout.SOUTH);

        var body = new JPanel(new BorderLayout(0, 12));
        body.setOpaque(false);
        body.add(fixtures, BorderLayout.NORTH);
        body.add(optics, BorderLayout.CENTER);
        body.add(sliderGroup, BorderLayout.SOUTH);
        setSelectedLight(selectedLight);
        return page("Lights", body);
    }

    private JPanel createObjectsPage() {
        var materials = new LinkedHashSet<String>();
        for (int i = 0; i < DisplayObjects.COUNT; i++) {
            materials.add(DisplayObjects.material(i));
        }
        material = new JComboBox<>(materials.toArray(String[]::new));
        object = new JComboBox<>();
        object.setPrototypeDisplayValue(new ObjectChoice(-1, "Medieval helmet"));
        styleCombo(material);
        styleCombo(object);

        var selectors = new JPanel(new GridLayout(0, 1, 0, 9));
        selectors.setOpaque(false);
        for (JComboBox<?> selector : new JComboBox<?>[]{material, object}) {
            var caption = label(selector == material ? "Material" : "Object", 16, MUTED);
            caption.setLabelFor(selector);
            selectors.add(caption);
            selectors.add(selector);
        }
        material.addActionListener(event -> {
            if (!syncingObject) {
                rebuildObjects(-1);
                chooseObject();
            }
        });
        object.addActionListener(event -> {
            if (!syncingObject) {
                chooseObject();
            }
        });
        var body = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        body.setOpaque(false);
        body.add(selectors);
        return page("Objects", body);
    }

    private JPanel createScenesPage() {
        var scenes = new JComboBox<>(ScenePreset.values());
        styleCombo(scenes);
        scenes.getAccessibleContext().setAccessibleName("Scene");
        scenes.addActionListener(event -> {
            if (scenes.getSelectedItem() instanceof ScenePreset selected) {
                onScene.accept(selected);
            }
        });
        var body = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        body.setOpaque(false);
        body.add(scenes);
        return page("Scenes", body);
    }

    private static JPanel page(String title, JPanel body) {
        var page = new JPanel(new BorderLayout(0, 15));
        page.setBackground(BACKGROUND);
        page.setBorder(BorderFactory.createEmptyBorder(13, 20, 14, 20));
        page.add(label(title, 26, TEXT), BorderLayout.NORTH);
        var content = new JPanel(new BorderLayout());
        content.setOpaque(false);
        content.add(body, BorderLayout.NORTH);
        page.add(content, BorderLayout.CENTER);
        return page;
    }

    private static JLabel label(String text, int size, Color color) {
        var label = new JLabel(text);
        label.setFont(new Font("Segoe UI", Font.PLAIN, size));
        label.setForeground(color);
        return label;
    }

    private static void styleCombo(JComboBox<?> combo) {
        combo.setFont(new Font("Segoe UI", Font.PLAIN, 17));
        combo.setBackground(SURFACE);
        combo.setForeground(TEXT);
        combo.setPreferredSize(new Dimension(285, 42));
        combo.setRenderer((list, value, index, selected, focused) -> {
            JLabel item = label(value == null ? "" : value.toString(), 17, TEXT);
            item.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 8));
            item.setOpaque(true);
            item.setBackground(selected ? SELECTED : SURFACE);
            return item;
        });
    }

    private void setSelectedLight(StudioLight light) {
        selectedLight = light;
        syncingLight = true;
        try {
            StudioLighting.Setting setting = settings.get(light);
            colorWheel.setRgb(setting.rgb());
            showHex(setting.rgb());
            colorWheel.getAccessibleContext().setAccessibleName(light.label + " light color");
            intensity.getAccessibleContext().setAccessibleName(light.label + " light intensity");
            intensity.setValue(setting.intensityPercent());
            intensityValue.setText(setting.intensityPercent() + "%");
            rows.values().forEach(JComponent::repaint);
        } finally {
            syncingLight = false;
        }
    }

    private void applyHexColor() {
        String hex = colorHex.getText().strip();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        if (!hex.matches("[0-9a-fA-F]{6}")) {
            colorHex.setForeground(new Color(0xFFA4AD));
            return;
        }
        applyColor(Integer.parseInt(hex, 16));
    }

    private void applyColor(int rgb) {
        StudioLighting.Setting old = settings.get(selectedLight);
        showHex(rgb);
        colorWheel.setRgb(rgb);
        updateLight(selectedLight, new StudioLighting.Setting(old.enabled(), rgb, old.intensityPercent()));
    }

    private void showHex(int rgb) {
        colorHex.setForeground(TEXT);
        colorHex.setText("#%06X".formatted(rgb));
    }

    private void updateLight(StudioLight light, StudioLighting.Setting setting) {
        if (!setting.equals(settings.put(light, setting))) {
            onLight.accept(light, setting);
        }
    }

    private void syncObject(int index) {
        syncingObject = true;
        try {
            material.setSelectedItem(DisplayObjects.material(index));
            rebuildObjects(index);
        } finally {
            syncingObject = false;
        }
    }

    private void rebuildObjects(int selected) {
        boolean previous = syncingObject;
        syncingObject = true;
        try {
            object.removeAllItems();
            String group = (String) material.getSelectedItem();
            for (int i = 0; i < DisplayObjects.COUNT; i++) {
                if (DisplayObjects.material(i).equals(group)) {
                    var choice = new ObjectChoice(i, DisplayObjects.objectName(i));
                    object.addItem(choice);
                    if (i == selected) {
                        object.setSelectedItem(choice);
                    }
                }
            }
        } finally {
            syncingObject = previous;
        }
    }

    private void chooseObject() {
        if (object.getSelectedItem() instanceof ObjectChoice choice) {
            onObject.accept(choice.index());
        }
    }

    private final class LightRow extends JPanel {
        private final StudioLight light;

        LightRow(StudioLight light, boolean enabled) {
            super(new BorderLayout(12, 0));
            this.light = light;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(10, 17, 10, 14));
            var name = new JButton(light.label);
            name.setFont(new Font("Segoe UI", Font.PLAIN, 18));
            name.setForeground(TEXT);
            name.setHorizontalAlignment(SwingConstants.LEFT);
            name.setBorder(BorderFactory.createEmptyBorder());
            name.setContentAreaFilled(false);
            name.addActionListener(event -> setSelectedLight(light));
            add(name, BorderLayout.CENTER);

            var toggle = new LightSwitch(light, enabled);
            toggle.addActionListener(event -> {
                StudioLighting.Setting old = settings.get(light);
                updateLight(light, new StudioLighting.Setting(toggle.isSelected(), old.rgb(), old.intensityPercent()));
            });
            add(toggle, BorderLayout.EAST);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D brush = smooth(g);
            brush.setColor(light == selectedLight ? SELECTED : SURFACE);
            brush.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);
            if (light == selectedLight) {
                brush.setColor(OUTLINE);
                brush.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 20, 20);
            }
            brush.dispose();
        }
    }

    private static final class LightSwitch extends JToggleButton {
        LightSwitch(StudioLight light, boolean enabled) {
            setSelected(enabled);
            setPreferredSize(new Dimension(46, 26));
            getAccessibleContext().setAccessibleName(light.label + " on or off");
            setContentAreaFilled(false);
            setBorderPainted(false);
            setToolTipText("Toggle " + light.label.toLowerCase() + " light");
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D brush = smooth(g);
            int y = (getHeight() - 25) / 2;
            brush.setColor(isSelected() ? ACCENT : new Color(0x3C3D52));
            brush.fillRoundRect(1, y, 44, 25, 25, 25);
            brush.setColor(TEXT);
            brush.fillOval(isSelected() ? 23 : 4, y + 3, 19, 19);
            if (isFocusOwner()) {
                brush.setColor(ACCENT.brighter());
                brush.drawRoundRect(0, y - 2, 45, 28, 25, 25);
            }
            brush.dispose();
        }
    }

    private static final class DashboardTab extends JToggleButton {
        DashboardTab(String caption) {
            super(caption);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 51));
            setPreferredSize(new Dimension(96, 51));
            setContentAreaFilled(false);
            setBorderPainted(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D brush = smooth(g);
            if (isSelected() || getModel().isRollover() || isFocusOwner()) {
                brush.setColor(isSelected() ? SELECTED : SURFACE);
                brush.fillRoundRect(0, 0, getWidth(), getHeight(), 19, 19);
            }
            brush.setColor(isSelected() ? ACCENT : MUTED);
            brush.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 24));
            String icon = switch (getText()) {
                case "Lights" -> "☼";
                case "Scenes" -> "▣";
                default -> "◇";
            };
            brush.drawString(icon, 10, 34);
            brush.setColor(isSelected() ? TEXT : MUTED);
            brush.setFont(new Font("Segoe UI", Font.PLAIN, 16));
            brush.drawString(getText(), 36, 31);
            brush.dispose();
        }
    }

    private static final class IntensitySliderUI extends BasicSliderUI {
        IntensitySliderUI(JSlider slider) {
            super(slider);
        }

        @Override
        protected Dimension getThumbSize() {
            return new Dimension(20, 20);
        }

        @Override
        public void paintTrack(Graphics g) {
            Graphics2D brush = smooth(g);
            int y = trackRect.y + trackRect.height / 2 - 5;
            brush.setColor(new Color(0x3C3D52));
            brush.fillRoundRect(trackRect.x, y, trackRect.width, 10, 10, 10);
            brush.setColor(ACCENT);
            int fill = thumbRect.x + thumbRect.width / 2 - trackRect.x;
            brush.fillRoundRect(trackRect.x, y, fill, 10, 10, 10);
            brush.dispose();
        }

        @Override
        public void paintThumb(Graphics g) {
            Graphics2D brush = smooth(g);
            brush.setColor(TEXT);
            brush.fillOval(thumbRect.x, thumbRect.y, thumbRect.width, thumbRect.height);
            brush.dispose();
        }

        @Override
        public void paintFocus(Graphics g) {
            Graphics2D brush = smooth(g);
            brush.setColor(OUTLINE);
            brush.drawRoundRect(0, 0, slider.getWidth() - 1, slider.getHeight() - 1, 8, 8);
            brush.dispose();
        }
    }

    private static Graphics2D smooth(Graphics g) {
        Graphics2D brush = (Graphics2D) g.create();
        brush.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        brush.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return brush;
    }

    @Override
    public void close() {
        EventQueue.invokeLater(() -> {
            if (window != null) {
                window.dispose();
            }
        });
    }
}
