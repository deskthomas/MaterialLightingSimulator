import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.GridLayout;
import java.awt.event.KeyEvent;
import java.util.LinkedHashSet;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

final class StudioControls implements AutoCloseable {
    private record ObjectChoice(int index, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final IntConsumer onObject;
    private final BiConsumer<StudioLight, StudioLighting.Setting> onLight;
    private JFrame window;
    private JComboBox<String> material;
    private JComboBox<ObjectChoice> object;
    private boolean syncing;

    StudioControls(IntConsumer onObject, BiConsumer<StudioLight, StudioLighting.Setting> onLight) {
        this.onObject = onObject;
        this.onLight = onLight;
    }

    void show(int selected, StudioLighting lighting) {
        EventQueue.invokeLater(() -> {
            if (window == null) {
                create(lighting);
            }
            syncObject(selected);
            window.setVisible(true);
            window.toFront();
            window.requestFocus();
        });
    }

    void selectObject(int index) {
        EventQueue.invokeLater(() -> {
            if (window != null) {
                syncObject(index);
            }
        });
    }

    private void create(StudioLighting lighting) {
        window = new JFrame("Material Lighting Simulator Controls");
        window.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        window.setLocationByPlatform(true);

        var materials = new LinkedHashSet<String>();
        for (int i = 0; i < DisplayObjects.COUNT; i++) {
            materials.add(DisplayObjects.material(i));
        }
        material = new JComboBox<>(materials.toArray(String[]::new));
        object = new JComboBox<>();
        object.setPrototypeDisplayValue(new ObjectChoice(-1, "Medieval helmet"));

        var selectors = new JPanel(new GridLayout(0, 1, 0, 5));
        var materialLabel = new JLabel("Material");
        materialLabel.setLabelFor(material);
        selectors.add(materialLabel);
        selectors.add(material);
        var objectLabel = new JLabel("Object");
        objectLabel.setLabelFor(object);
        selectors.add(objectLabel);
        selectors.add(object);

        var fixtures = new JPanel(new GridLayout(0, 1, 0, 8));
        fixtures.setBorder(BorderFactory.createTitledBorder("Light fixtures"));
        for (StudioLight light : StudioLight.values()) {
            fixtures.add(createLightControls(light, lighting.get(light)));
        }

        var content = new JPanel(new BorderLayout(0, 14));
        content.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        content.add(selectors, BorderLayout.NORTH);
        content.add(fixtures, BorderLayout.CENTER);
        window.setContentPane(content);
        window.getRootPane().registerKeyboardAction(event -> window.setVisible(false),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        window.pack();
        window.setMinimumSize(window.getSize());

        material.addActionListener(event -> {
            if (!syncing) {
                rebuildObjects(-1);
                chooseObject();
            }
        });
        object.addActionListener(event -> {
            if (!syncing) {
                chooseObject();
            }
        });
    }

    private JPanel createLightControls(StudioLight light, StudioLighting.Setting setting) {
        var enabled = new JCheckBox(light.label, setting.enabled());
        var color = new JButton();
        color.setOpaque(true);
        color.getAccessibleContext().setAccessibleName(light.label + " light color");
        showColor(color, new Color(setting.rgb()));

        var intensity = new JSlider(0, StudioLighting.MAX_INTENSITY_PERCENT, setting.intensityPercent());
        intensity.getAccessibleContext().setAccessibleName(light.label + " light intensity");
        var intensityLabel = new JLabel("Intensity");
        intensityLabel.setLabelFor(intensity);
        var value = new JLabel(setting.intensityPercent() + "%", JLabel.TRAILING);
        value.setPreferredSize(new JLabel("200%").getPreferredSize());

        Runnable update = () -> onLight.accept(light, new StudioLighting.Setting(
                enabled.isSelected(), color.getBackground().getRGB() & 0xffffff, intensity.getValue()));
        enabled.addActionListener(event -> update.run());
        intensity.addChangeListener(event -> {
            value.setText(intensity.getValue() + "%");
            update.run();
        });
        color.addActionListener(event -> {
            Color chosen = JColorChooser.showDialog(window, light.label + " light color", color.getBackground());
            if (chosen != null) {
                showColor(color, chosen);
                update.run();
            }
        });

        var header = new JPanel(new BorderLayout(8, 0));
        header.add(enabled, BorderLayout.WEST);
        header.add(color, BorderLayout.EAST);
        var sliderRow = new JPanel(new BorderLayout(8, 0));
        sliderRow.add(intensityLabel, BorderLayout.WEST);
        sliderRow.add(intensity, BorderLayout.CENTER);
        sliderRow.add(value, BorderLayout.EAST);

        var row = new JPanel(new BorderLayout(0, 3));
        row.add(header, BorderLayout.NORTH);
        row.add(sliderRow, BorderLayout.CENTER);
        return row;
    }

    private static void showColor(JButton button, Color color) {
        button.setText("Color #%06X".formatted(color.getRGB() & 0xffffff));
        button.setBackground(color);
        int luminance = 299 * color.getRed() + 587 * color.getGreen() + 114 * color.getBlue();
        button.setForeground(luminance >= 128_000 ? Color.BLACK : Color.WHITE);
    }

    private void syncObject(int index) {
        syncing = true;
        try {
            material.setSelectedItem(DisplayObjects.material(index));
        } finally {
            syncing = false;
        }
        rebuildObjects(index);
    }

    private void rebuildObjects(int selected) {
        syncing = true;
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
            syncing = false;
        }
    }

    private void chooseObject() {
        if (object.getSelectedItem() instanceof ObjectChoice choice) {
            onObject.accept(choice.index());
        }
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
