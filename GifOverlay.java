import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.zip.ZipFile;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import org.w3c.dom.Node;

public final class GifOverlay {
    private final String windowTitle = "GifOverlay " + UUID.randomUUID();
    private final JFrame window = new JFrame(windowTitle);
    private final JFrame controls = new JFrame("GifOverlay");
    private final Drawing drawing = new Drawing();
    private final Timer timer = new Timer(100, event -> advance());
    private final JButton openGif = new JButton("Open animation…");
    private final JCheckBox sound = new JCheckBox("Play sound");
    private final JCheckBox clickThrough = new JCheckBox("Click through");
    private final JSpinner scale = new JSpinner(new SpinnerNumberModel(100, 10, 400, 5));
    private final JSlider volume = new JSlider(0, 100, 100);
    private final JLabel volumeLabel = new JLabel();
    private final Properties savedSettings = new Properties();
    private Path settingsFile;
    private final JLabel animationFile = new JLabel("Animation: none");
    private final JLabel soundFile = new JLabel("Sound: none");
    private boolean bundled;
    private boolean overlayZip;
    private final JComboBox<Integer> resolution = new JComboBox<>(new Integer[]{100, 90, 80, 75, 70, 60, 50, 40, 30, 25});
    private File loadedAnimationFile;
    private final JCheckBox customDelay = new JCheckBox("Fixed ZIP frame delay");
    private final JSpinner delayMs = new JSpinner(new SpinnerNumberModel(40, 1, 60_000, 1));
    private TrayIcon tray;
    private Clip audio;
    private Animation animation;
    private int frameIndex;
    private WindowsStyles styles;

    public static void main(String[] args) {
        boolean bundled = args.length == 1 && args[0].equals("--anoko");
        SwingUtilities.invokeLater(() -> new GifOverlay().start(bundled));
    }

    private void start(boolean bundled) {
        this.bundled = bundled;
        String appData = System.getenv("LOCALAPPDATA");
        Path base = appData == null || appData.isBlank()
                ? Path.of(System.getProperty("user.home")) : Path.of(appData);
        settingsFile = base.resolve(bundled ? "AnokoOverlay" : "GifOverlay").resolve("settings.properties");
        restoreSettings();
        window.setUndecorated(true);
        window.setBackground(new Color(0, 0, 0, 0));
        window.setAlwaysOnTop(true);
        window.setFocusableWindowState(false);
        window.add(drawing);
        timer.setRepeats(false);

        MouseAdapter drag = new MouseAdapter() {
            private Point offset;

            @Override
            public void mousePressed(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event)) {
                    offset = event.getPoint();
                }
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (offset != null) {
                    Point cursor = event.getLocationOnScreen();
                    window.setLocation(cursor.x - offset.x, cursor.y - offset.y);
                }
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (offset != null) {
                    saveSettings();
                }
                offset = null;
            }
        };
        drawing.addMouseListener(drag);
        drawing.addMouseMotionListener(drag);

        openGif.addActionListener(event -> chooseGif());
        JButton openSound = new JButton("Open WAV…");
        openSound.addActionListener(event -> chooseSound());
        sound.setEnabled(false);
        sound.addActionListener(event -> {
            if (sound.isSelected()) {
                audio.setFramePosition(0);
                audio.loop(Clip.LOOP_CONTINUOUSLY);
            } else {
                audio.stop();
            }
            saveSettings();
        });
        clickThrough.setEnabled(false);
        clickThrough.addActionListener(event -> {
            boolean requested = clickThrough.isSelected();
            try {
                if (styles == null) {
                    styles = new WindowsStyles();
                }
                styles.setClickThrough(windowTitle, requested);
            } catch (Throwable failure) {
                clickThrough.setSelected(!requested);
                showError("Could not change click-through mode", failure);
            }
        });

        JPanel settings = new JPanel(new GridLayout(0, 2, 8, 8));
        settings.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        if (!bundled) {
            settings.add(openGif);
            settings.add(openSound);
            JButton library = new JButton("Library…");
            library.addActionListener(event -> {
                if (openGif.isEnabled()) {
                    showLibrary();
                }
            });
            settings.add(library);
            settings.add(new JLabel());
            customDelay.setToolTipText("Unchecked: use delays.txt");
            delayMs.setToolTipText("Milliseconds per frame");
            delayMs.setEnabled(customDelay.isSelected());
            customDelay.addActionListener(event -> updateFrameTiming());
            delayMs.addChangeListener(event -> updateFrameTiming());
            settings.add(customDelay);
            settings.add(delayMs);
            JLabel quality = new JLabel("Frame resolution (%)");
            quality.setLabelFor(resolution);
            settings.add(quality);
            settings.add(resolution);
            resolution.addActionListener(event -> {
                if (!resolution.isEnabled()) {
                    return;
                }
                if (loadedAnimationFile != null) {
                    loadGif(loadedAnimationFile);
                } else {
                    saveSettings();
                }
            });
        }
        JLabel size = new JLabel("Size (%)");
        size.setLabelFor(scale);
        settings.add(size);
        settings.add(scale);
        volumeLabel.setLabelFor(volume);
        updateVolumeLabel();
        volume.setEnabled(!bundled);
        volume.addChangeListener(event -> {
            updateVolumeLabel();
            if (audio != null) {
                applyVolume(audio);
            }
            if (!volume.getValueIsAdjusting()) {
                saveSettings();
            }
        });
        settings.add(volumeLabel);
        settings.add(volume);
        settings.add(sound);
        settings.add(clickThrough);
        JButton exit = new JButton("Exit");
        exit.addActionListener(event -> close());
        settings.add(exit);
        scale.addChangeListener(event -> {
            resizeOverlay();
            saveSettings();
        });
        JLabel dropHint = new JLabel(bundled ? "Anoko — Papaya" : "Drop GIF, overlay ZIP or WAV here",
                SwingConstants.CENTER);
        dropHint.setBorder(BorderFactory.createEmptyBorder(12, 12, 0, 12));
        JPanel content = new JPanel(new BorderLayout());
        content.add(dropHint, BorderLayout.NORTH);
        content.add(settings, BorderLayout.CENTER);
        if (!bundled) {
            JPanel files = new JPanel(new GridLayout(0, 1, 0, 4));
            files.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));
            files.add(animationFile);
            files.add(soundFile);
            content.add(files, BorderLayout.SOUTH);
            content.setTransferHandler(new TransferHandler() {
                @Override
                public boolean canImport(TransferSupport transfer) {
                    return transfer.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
                }

                @Override
                public boolean importData(TransferSupport transfer) {
                    if (!canImport(transfer)) {
                        return false;
                    }
                    try {
                        List<?> files = (List<?>) transfer.getTransferable()
                                .getTransferData(DataFlavor.javaFileListFlavor);
                        return loadDroppedFiles(files);
                    } catch (IOException | UnsupportedFlavorException failure) {
                        showError("Could not read dropped files", failure);
                        return false;
                    }
                }
            });
        }
        controls.setContentPane(content);
        controls.pack();
        controls.setLocationByPlatform(true);

        installTray();
        controls.setDefaultCloseOperation(tray == null
                ? WindowConstants.EXIT_ON_CLOSE : WindowConstants.HIDE_ON_CLOSE);
        controls.setVisible(true);
        if (bundled) {
            loadBundledMedia();
        } else {
            File lastAnimation = recentFile("animationFile", animationFile, "Animation");
            File lastSound = recentFile("soundFile", soundFile, "Sound");
            if (lastAnimation != null) {
                loadGif(lastAnimation);
            }
            if (lastSound != null) {
                loadSound(lastSound);
            }
        }
    }

    private File recentFile(String key, JLabel label, String type) {
        String path = savedSettings.getProperty(key);
        if (path == null || path.isBlank()) {
            return null;
        }
        File file = new File(path);
        if (file.isFile()) {
            showMediaFile(label, type, file, "");
            return file;
        }
        showMediaFile(label, type, file, " (missing)");
        return null;
    }

    private void showMediaFile(JLabel label, String type, File file, String status) {
        String name = file.getName();
        if (name.length() > 38) {
            name = name.substring(0, 24) + "…" + name.substring(name.length() - 12);
        }
        label.setText(type + ": " + name + status);
        label.setToolTipText(file.getAbsolutePath());
    }

    private void rememberMedia(String key, JLabel label, String type, File file) {
        if (!bundled) {
            showMediaFile(label, type, file, "");
            savedSettings.setProperty(key, file.getAbsolutePath());
            saveSettings();
        }
    }

    private void showLibrary() {
        var model = new DefaultListModel<String>();
        savedSettings.stringPropertyNames().stream()
                .filter(key -> key.startsWith("preset.") && key.endsWith(".name"))
                .sorted(java.util.Comparator.comparing(savedSettings::getProperty))
                .forEach(model::addElement);
        var list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(8);
        list.setCellRenderer((view, key, index, selected, focused) ->
                new DefaultListCellRenderer().getListCellRendererComponent(
                        view, savedSettings.getProperty(key), index, selected, focused));
        var dialog = new JDialog(controls, "Library", true);
        var buttons = new JPanel();
        JButton load = new JButton("Load");
        load.addActionListener(event -> {
            String key = list.getSelectedValue();
            if (key != null) {
                String prefix = key.substring(0, key.length() - 4);
                File image = new File(savedSettings.getProperty(prefix + "animationFile", ""));
                String wav = savedSettings.getProperty(prefix + "soundFile", "");
                File soundPath = wav.isEmpty() ? null : new File(wav);
                if (!image.isFile() || (soundPath != null && !soundPath.isFile())) {
                    JOptionPane.showMessageDialog(dialog, "A saved file is missing. Use Relink to update its path.");
                    return;
                }
                dialog.dispose();
                loadGif(image, prefix, soundPath);
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int index = list.locationToIndex(event.getPoint());
                if (event.getClickCount() == 2 && index >= 0
                        && list.getCellBounds(index, index).contains(event.getPoint())) {
                    load.doClick();
                }
            }
        });
        JButton add = new JButton("Save current");
        add.setEnabled(animation != null);
        add.addActionListener(event -> {
            String name = JOptionPane.showInputDialog(dialog, "Combination name");
            if (name == null || name.isBlank()) {
                return;
            }
            saveSettings();
            String prefix = "preset." + UUID.randomUUID() + ".";
            for (String key : List.of("animationFile", "soundFile", "x", "y", "scale", "volume",
                    "sound", "resolution", "customDelay", "delayMs")) {
                String value = savedSettings.getProperty(key);
                if (value != null && (!key.equals("soundFile") || audio != null)) {
                    savedSettings.setProperty(prefix + key, value);
                }
            }
            savedSettings.setProperty(prefix + "name", name.strip());
            model.addElement(prefix + "name");
            list.setSelectedIndex(model.size() - 1);
            saveSettings();
        });
        JButton rename = new JButton("Rename");
        rename.addActionListener(event -> {
            String key = list.getSelectedValue();
            if (key != null) {
                String name = JOptionPane.showInputDialog(dialog, "Combination name", savedSettings.getProperty(key));
                if (name != null && !name.isBlank()) {
                    savedSettings.setProperty(key, name.strip());
                    list.repaint();
                    saveSettings();
                }
            }
        });
        JButton relink = new JButton("Relink");
        relink.addActionListener(event -> {
            String key = list.getSelectedValue();
            if (key == null) {
                return;
            }
            Object[] options = {"Animation", "WAV", "No WAV"};
            int choice = JOptionPane.showOptionDialog(dialog, "Choose the file to replace", "Relink",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
            String prefix = key.substring(0, key.length() - 4);
            if (choice == 2) {
                savedSettings.remove(prefix + "soundFile");
            } else if (choice >= 0) {
                File file = choice == 0 ? chooseFile(dialog, "GIF or overlay ZIP", "gif", "zip")
                        : chooseFile(dialog, "WAV audio", "wav");
                if (file == null) {
                    return;
                }
                savedSettings.setProperty(prefix + (choice == 0 ? "animationFile" : "soundFile"),
                        file.getAbsolutePath());
            } else {
                return;
            }
            saveSettings();
        });
        JButton delete = new JButton("Delete");
        delete.addActionListener(event -> {
            String key = list.getSelectedValue();
            if (key != null && JOptionPane.showConfirmDialog(dialog, "Delete this saved combination?",
                    "Library", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                String prefix = key.substring(0, key.length() - 4);
                savedSettings.keySet().removeIf(item -> item.toString().startsWith(prefix));
                model.removeElement(key);
                saveSettings();
            }
        });
        for (JButton button : List.of(load, add, rename, relink, delete)) {
            buttons.add(button);
        }
        dialog.add(new JScrollPane(list), BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.getRootPane().setDefaultButton(load);
        dialog.pack();
        dialog.setLocationRelativeTo(controls);
        dialog.setVisible(true);
    }

    private void restoreSettings() {
        if (Files.isRegularFile(settingsFile)) {
            try (var input = Files.newInputStream(settingsFile)) {
                savedSettings.load(input);
            } catch (IOException | IllegalArgumentException failure) {
                savedSettings.clear();
            }
        }
        scale.setValue(Math.clamp(savedInt("scale", 100), 10, 400));
        volume.setValue(Math.clamp(savedInt("volume", 100), 0, 100));
        sound.setSelected(Boolean.parseBoolean(savedSettings.getProperty("sound", "false")));
        resolution.setSelectedItem(savedInt("resolution", 100));
        customDelay.setSelected(Boolean.parseBoolean(savedSettings.getProperty("customDelay", "false")));
        delayMs.setValue(Math.clamp(savedInt("delayMs", 40), 1, 60_000));
    }

    private int savedInt(String key, int fallback) {
        try {
            return Integer.parseInt(savedSettings.getProperty(key));
        } catch (NumberFormatException failure) {
            return fallback;
        }
    }

    private void saveSettings() {
        if (settingsFile == null) {
            return;
        }
        savedSettings.setProperty("scale", scale.getValue().toString());
        savedSettings.setProperty("volume", Integer.toString(volume.getValue()));
        savedSettings.setProperty("sound", Boolean.toString(sound.isSelected()));
        if (!bundled) {
            savedSettings.setProperty("resolution", resolution.getSelectedItem().toString());
            savedSettings.setProperty("customDelay", Boolean.toString(customDelay.isSelected()));
            savedSettings.setProperty("delayMs", delayMs.getValue().toString());
        }
        if (window.isVisible()) {
            savedSettings.setProperty("x", Integer.toString(window.getX()));
            savedSettings.setProperty("y", Integer.toString(window.getY()));
        }
        Path pending = null;
        try {
            Path directory = Files.createDirectories(settingsFile.getParent());
            pending = Files.createTempFile(directory, "settings-", ".tmp");
            try (var output = Files.newOutputStream(pending)) {
                savedSettings.store(output, bundled ? "AnokoOverlay" : "GifOverlay");
            }
            try {
                Files.move(pending, settingsFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException failure) {
                Files.move(pending, settingsFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            System.err.println("Could not save overlay settings: " + failure.getMessage());
        } finally {
            if (pending != null) {
                try {
                    Files.deleteIfExists(pending);
                } catch (IOException ignored) {
                    // The next save can still create a fresh temporary file.
                }
            }
        }
    }

    private void updateVolumeLabel() {
        volumeLabel.setText("Volume (" + volume.getValue() + "%)");
    }

    private boolean applyVolume(Clip clip) {
        float level = volume.getValue() / 100f;
        if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            float decibels = level == 0 ? gain.getMinimum() : (float) (20 * Math.log10(level));
            gain.setValue(Math.clamp(decibels, gain.getMinimum(), gain.getMaximum()));
        } else if (clip.isControlSupported(FloatControl.Type.VOLUME)) {
            FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.VOLUME);
            gain.setValue(Math.clamp(level, gain.getMinimum(), gain.getMaximum()));
        } else {
            return false;
        }
        if (clip.isControlSupported(BooleanControl.Type.MUTE)) {
            ((BooleanControl) clip.getControl(BooleanControl.Type.MUTE)).setValue(level == 0);
        }
        return true;
    }

    private void loadBundledMedia() {
        try {
            Path assets = Path.of(GifOverlay.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getParent();
            loadGif(assets.resolve("Anoko.overlay.zip").toFile());
            loadSound(assets.resolve("Anoko.wav").toFile());
        } catch (Exception failure) {
            showError("Could not locate bundled Anoko files", failure);
        }
    }

    private void installTray() {
        if (!SystemTray.isSupported()) {
            return;
        }
        BufferedImage icon = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = icon.createGraphics();
        graphics.setColor(new Color(37, 87, 172));
        graphics.fillRoundRect(0, 0, 24, 24, 8, 8);
        graphics.setColor(Color.WHITE);
        graphics.fillPolygon(new int[]{9, 9, 17}, new int[]{6, 18, 12}, 3);
        graphics.dispose();

        PopupMenu menu = new PopupMenu();
        MenuItem settings = new MenuItem("Settings");
        settings.addActionListener(event -> SwingUtilities.invokeLater(this::showControls));
        menu.add(settings);
        MenuItem exit = new MenuItem("Exit");
        exit.addActionListener(event -> SwingUtilities.invokeLater(this::close));
        menu.add(exit);

        TrayIcon candidate = new TrayIcon(icon, "GifOverlay", menu);
        candidate.setImageAutoSize(true);
        candidate.addActionListener(event -> SwingUtilities.invokeLater(this::showControls));
        try {
            SystemTray.getSystemTray().add(candidate);
            tray = candidate;
        } catch (AWTException ignored) {
            // Keep the settings window available when Windows cannot add the tray icon.
        }
    }

    private void showControls() {
        controls.setExtendedState(Frame.NORMAL);
        controls.setVisible(true);
        controls.toFront();
    }

    private File chooseFile(String type, String... extensions) {
        return chooseFile(controls, type, extensions);
    }

    private File chooseFile(Component owner, String type, String... extensions) {
        String mediaKey = extensions[0].equals("wav") ? "soundFile" : "animationFile";
        String otherKey = mediaKey.equals("soundFile") ? "animationFile" : "soundFile";
        File directory = mediaDirectory(mediaKey);
        if (directory == null && mediaKey.equals("animationFile")) {
            File images = new File(System.getProperty("user.dir"), "Images");
            if (images.isDirectory()) {
                directory = images;
            }
        }
        if (directory == null) {
            directory = mediaDirectory(otherKey);
        }
        if (directory == null) {
            directory = new File(System.getProperty("user.dir"));
        }
        JFileChooser picker = new JFileChooser(directory);
        picker.setFileFilter(new FileNameExtensionFilter(type, extensions));
        return picker.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION
                ? picker.getSelectedFile() : null;
    }

    private File mediaDirectory(String key) {
        String path = savedSettings.getProperty(key);
        if (path == null || path.isBlank()) {
            return null;
        }
        File directory = new File(path).getAbsoluteFile().getParentFile();
        return directory != null && directory.isDirectory() ? directory : null;
    }

    private void chooseGif() {
        File selected = chooseFile("GIF or overlay ZIP", "gif", "zip");
        if (selected != null) {
            loadGif(selected);
        }
    }

    private boolean loadDroppedFiles(List<?> files) {
        File gif = null;
        File wav = null;
        for (Object item : files) {
            if (!(item instanceof File file) || !file.isFile()) {
                continue;
            }
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (gif == null && (name.endsWith(".gif") || name.endsWith(".overlay.zip"))) {
                gif = file;
            }
            if (wav == null && name.endsWith(".wav")) {
                wav = file;
            }
        }
        if (gif == null && wav == null) {
            showError("No supported file", new IOException("Drop a GIF, overlay ZIP or WAV file."));
            return false;
        }
        if (gif != null && !openGif.isEnabled()) {
            showError("GIF is still loading", new IOException("Drop the files again when it finishes."));
            return false;
        }
        if (gif != null) {
            loadGif(gif);
        }
        if (wav != null) {
            loadSound(wav);
        }
        return true;
    }

    private void loadGif(File selected) {
        loadGif(selected, null, null);
    }

    private void loadGif(File selected, String preset, File wav) {
        openGif.setEnabled(false);
        resolution.setEnabled(false);
        int percent = preset != null ? savedInt(preset + "resolution", 100)
                : bundled ? 100 : (Integer) resolution.getSelectedItem();
        new SwingWorker<Animation, Void>() {
            private Clip preparedSound;

            @Override
            protected Animation doInBackground() throws Exception {
                Animation decoded = Animation.read(selected, percent);
                if (wav != null) {
                    try (AudioInputStream stream = AudioSystem.getAudioInputStream(wav)) {
                        preparedSound = AudioSystem.getClip();
                        preparedSound.open(stream);
                    }
                }
                return decoded;
            }

            @Override
            protected void done() {
                try {
                    Animation loaded = get();
                    boolean firstImage = animation == null;
                    timer.stop();
                    animation = loaded;
                    loadedAnimationFile = selected;
                    overlayZip = selected.getName().toLowerCase(Locale.ROOT).endsWith(".overlay.zip");
                    frameIndex = 0;
                    if (preset != null) {
                        applyPreset(preset, percent, wav, preparedSound);
                        preparedSound = null;
                    }
                    if (firstImage || preset != null) {
                        placeInitialOverlay();
                        window.setVisible(true);
                        clickThrough.setEnabled(tray != null);
                    } else {
                        resizeOverlay();
                    }
                    displayFrame();
                    rememberMedia("animationFile", animationFile, "Animation", selected);
                } catch (ExecutionException failure) {
                    if (!bundled && animation == null) {
                        showMediaFile(animationFile, "Animation", selected, " (load failed)");
                    }
                    showError("Could not open animation", failure.getCause());
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                } finally {
                    if (preparedSound != null) {
                        preparedSound.close();
                    }
                    openGif.setEnabled(true);
                    resolution.setEnabled(true);
                }
            }
        }.execute();
    }

    private void applyPreset(String prefix, int percent, File wav, Clip replacement) {
        if (audio != null) {
            audio.close();
        }
        audio = null;
        resolution.setSelectedItem(percent);
        scale.setValue(savedInt(prefix + "scale", 100));
        volume.setValue(savedInt(prefix + "volume", 100));
        customDelay.setSelected(Boolean.parseBoolean(savedSettings.getProperty(prefix + "customDelay")));
        delayMs.setValue(savedInt(prefix + "delayMs", 40));
        updateFrameTiming();
        for (String axis : List.of("x", "y")) {
            savedSettings.setProperty(axis, Integer.toString(savedInt(prefix + axis, Integer.MIN_VALUE)));
        }
        audio = replacement;
        sound.setEnabled(audio != null);
        sound.setSelected(audio != null && Boolean.parseBoolean(savedSettings.getProperty(prefix + "sound")));
        volume.setEnabled(audio == null || applyVolume(audio));
        if (wav == null) {
            savedSettings.remove("soundFile");
            soundFile.setText("Sound: none");
            soundFile.setToolTipText(null);
        } else {
            savedSettings.setProperty("soundFile", wav.getAbsolutePath());
            showMediaFile(soundFile, "Sound", wav, "");
            if (sound.isSelected()) {
                audio.loop(Clip.LOOP_CONTINUOUSLY);
            }
        }
    }

    private void resizeOverlay() {
        if (animation == null) {
            return;
        }
        resizeOverlay(window.getGraphicsConfiguration().getBounds());
    }

    private void placeInitialOverlay() {
        Rectangle bounds = controls.getGraphicsConfiguration().getBounds();
        Point saved = new Point(savedInt("x", Integer.MIN_VALUE), savedInt("y", Integer.MIN_VALUE));
        for (GraphicsDevice display : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle screen = display.getDefaultConfiguration().getBounds();
            if (screen.contains(saved)) {
                window.setLocation(saved);
                resizeOverlay(screen);
                return;
            }
        }
        window.setLocation(bounds.getLocation());
        resizeOverlay(bounds);
        window.setLocation(bounds.x + Math.max(0, bounds.width - window.getWidth() - 32),
                bounds.y + Math.max(0, Math.min(48, bounds.height - window.getHeight())));
    }

    private void resizeOverlay(Rectangle bounds) {
        int width = animation.width();
        int height = animation.height();
        double factor = Math.min((Integer) scale.getValue() / 100.0,
                Math.min((double) bounds.width / width,
                        (double) bounds.height / height));
        window.setSize(Math.max(1, (int) Math.round(width * factor)),
                Math.max(1, (int) Math.round(height * factor)));
        window.setLocation(
                Math.clamp(window.getX(), bounds.x, bounds.x + bounds.width - window.getWidth()),
                Math.clamp(window.getY(), bounds.y, bounds.y + bounds.height - window.getHeight()));
        drawing.repaint();
    }

    private void updateFrameTiming() {
        delayMs.setEnabled(customDelay.isSelected());
        if (animation != null && overlayZip) {
            displayFrame();
        }
        saveSettings();
    }

    private boolean usesFixedDelay() {
        return !bundled && overlayZip && customDelay.isSelected();
    }

    private int frameDelay(int index) {
        return usesFixedDelay() ? (Integer) delayMs.getValue() : animation.frames().get(index).delay();
    }

    private boolean followsAudio() {
        return audio != null && sound.isSelected() && audio.isRunning()
                && !usesFixedDelay()
                && Math.abs(audio.getMicrosecondLength() - animation.durationMicros()) <= 10_000;
    }

    private void advance() {
        if (followsAudio()) {
            long position = Math.floorMod(audio.getMicrosecondPosition(), animation.durationMicros());
            frameIndex = 0;
            while (frameIndex < animation.frames().size() - 1) {
                long frameDuration = animation.frames().get(frameIndex).delay() * 1_000L;
                if (position < frameDuration) {
                    break;
                }
                position -= frameDuration;
                frameIndex++;
            }
        } else {
            frameIndex = (frameIndex + 1) % animation.frames().size();
        }
        displayFrame();
    }

    private void displayFrame() {
        drawing.repaint();
        if (animation.frames().size() > 1) {
            timer.setInitialDelay(followsAudio() ? 8 : frameDelay(frameIndex));
            timer.restart();
        }
    }

    private void chooseSound() {
        File selected = chooseFile("WAV audio", "wav");
        if (selected != null) {
            loadSound(selected);
        }
    }

    private void loadSound(File selected) {
        Clip replacement = null;
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(selected)) {
            replacement = AudioSystem.getClip();
            replacement.open(stream);
            boolean volumeAvailable = applyVolume(replacement);
            if (sound.isSelected()) {
                replacement.loop(Clip.LOOP_CONTINUOUSLY);
            }
            if (audio != null) {
                audio.close();
            }
            audio = replacement;
            sound.setEnabled(true);
            volume.setEnabled(volumeAvailable);
            rememberMedia("soundFile", soundFile, "Sound", selected);
        } catch (Exception failure) {
            if (!bundled && audio == null) {
                showMediaFile(soundFile, "Sound", selected, " (load failed)");
            }
            if (replacement != null) {
                replacement.close();
            }
            showError("Could not open WAV", failure);
        }
    }

    private void showError(String message, Throwable failure) {
        JOptionPane.showMessageDialog(controls, message + "\n" + failure.getMessage(),
                "GifOverlay", JOptionPane.ERROR_MESSAGE);
    }

    private void close() {
        saveSettings();
        timer.stop();
        if (audio != null) {
            audio.close();
        }
        if (tray != null) {
            SystemTray.getSystemTray().remove(tray);
        }
        window.dispose();
        controls.dispose();
        System.exit(0);
    }

    private final class Drawing extends JComponent {
        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D canvas = (Graphics2D) graphics.create();
            canvas.setComposite(AlphaComposite.Src);
            canvas.setColor(new Color(0, 0, 0, 0));
            canvas.fillRect(0, 0, getWidth(), getHeight());
            if (animation != null) {
                canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                canvas.drawImage(animation.frames().get(frameIndex).image(),
                        0, 0, getWidth(), getHeight(), null);
            }
            canvas.dispose();
        }
    }

    private record GifFrame(BufferedImage image, int delay) {}

    private record Animation(List<GifFrame> frames, long durationMicros, int width, int height) {
        private static Animation read(File file) throws IOException {
            return read(file, 100);
        }

        private static Animation read(File file, int percent) throws IOException {
            if (file.getName().toLowerCase(Locale.ROOT).endsWith(".overlay.zip")) {
                return readOverlay(file, percent);
            }
            ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
            try (ImageInputStream input = ImageIO.createImageInputStream(file)) {
                if (input == null) {
                    throw new IOException("Cannot read " + file.getName());
                }
                reader.setInput(input);
                Node stream = reader.getStreamMetadata().getAsTree("javax_imageio_gif_stream_1.0");
                Node screen = child(stream, "LogicalScreenDescriptor");
                int width = number(screen, "logicalScreenWidth");
                int height = number(screen, "logicalScreenHeight");
                int count = reader.getNumImages(true);
                if (width <= 0 || height <= 0 || count <= 0
                        || (long) width * height > 120_000_000L / count) {
                    throw new IOException("GIF is empty or too large to decode.");
                }
                BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                List<GifFrame> frames = new ArrayList<>(count);
                for (int index = 0; index < count; index++) {
                    Node metadata = reader.getImageMetadata(index)
                            .getAsTree("javax_imageio_gif_image_1.0");
                    Node descriptor = child(metadata, "ImageDescriptor");
                    Node control = child(metadata, "GraphicControlExtension");
                    int x = number(descriptor, "imageLeftPosition");
                    int y = number(descriptor, "imageTopPosition");
                    BufferedImage patch = reader.read(index);
                    String disposal = control == null ? "none" : attribute(control, "disposalMethod");
                    BufferedImage previous = disposal.equals("restoreToPrevious") ? copy(canvas) : null;
                    Graphics2D graphics = canvas.createGraphics();
                    graphics.drawImage(patch, x, y, null);
                    int delay = control == null ? 100 : number(control, "delayTime") * 10;
                    frames.add(new GifFrame(scaledFrame(canvas, percent, true), delay == 0 ? 100 : delay));
                    if (disposal.equals("restoreToBackgroundColor")) {
                        graphics.setComposite(AlphaComposite.Clear);
                        graphics.fillRect(x, y, patch.getWidth(), patch.getHeight());
                    }
                    graphics.dispose();
                    if (previous != null) {
                        canvas = previous;
                    }
                }
                long duration = frames.stream().mapToLong(frame -> frame.delay() * 1_000L).sum();
                return new Animation(frames, duration, width, height);
            } finally {
                reader.dispose();
            }
        }

        private static Animation readOverlay(File file, int percent) throws IOException {
            try (ZipFile zip = new ZipFile(file)) {
                var timing = zip.getEntry("delays.txt");
                if (timing == null) {
                    throw new IOException("Overlay timing file is missing.");
                }
                String[] delays;
                try (var input = zip.getInputStream(timing)) {
                    byte[] bytes = input.readNBytes(8193);
                    if (bytes.length > 8192) {
                        throw new IOException("Overlay timing file is too large.");
                    }
                    delays = new String(bytes, java.nio.charset.StandardCharsets.US_ASCII)
                            .strip().split("\\s+");
                }
                List<GifFrame> frames = new ArrayList<>(delays.length);
                long duration = 0;
                long pixels = 0;
                int width = 0;
                int height = 0;
                for (int index = 0; index < delays.length; index++) {
                    int delay;
                    try {
                        delay = Integer.parseInt(delays[index]);
                    } catch (NumberFormatException failure) {
                        throw new IOException("Invalid overlay frame delay.", failure);
                    }
                    var entry = zip.getEntry("frames/" + index + ".png");
                    if (delay <= 0 || delay > 60_000 || entry == null || entry.getSize() > 8_000_000) {
                        throw new IOException("Invalid overlay frame " + index + ".");
                    }
                    ImageReader reader = ImageIO.getImageReadersByFormatName("png").next();
                    try (var bytes = zip.getInputStream(entry);
                         ImageInputStream input = ImageIO.createImageInputStream(bytes)) {
                        reader.setInput(input, true, true);
                        int frameWidth = reader.getWidth(0);
                        int frameHeight = reader.getHeight(0);
                        if (index == 0) {
                            width = frameWidth;
                            height = frameHeight;
                        }
                        pixels += (long) frameWidth * frameHeight;
                        if (frameWidth != width || frameHeight != height || pixels > 120_000_000) {
                            throw new IOException("Overlay frames are too large or have different sizes.");
                        }
                        frames.add(new GifFrame(scaledFrame(reader.read(0), percent, false), delay));
                    } finally {
                        reader.dispose();
                    }
                    duration += delay * 1_000L;
                }
                return new Animation(frames, duration, width, height);
            }
        }

        private static BufferedImage scaledFrame(BufferedImage image, int percent, boolean copyAtOriginalSize) {
            if (percent == 100) {
                return copyAtOriginalSize ? copy(image) : image;
            }
            int width = Math.max(1, Math.round(image.getWidth() * percent / 100f));
            int height = Math.max(1, Math.round(image.getHeight() * percent / 100f));
            BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = scaled.createGraphics();
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, width, height, null);
            graphics.dispose();
            return scaled;
        }

        private static BufferedImage copy(BufferedImage image) {
            BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), image.getType());
            Graphics2D graphics = copy.createGraphics();
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(image, 0, 0, null);
            graphics.dispose();
            return copy;
        }

        private static Node child(Node parent, String name) {
            for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node.getNodeName().equals(name)) {
                    return node;
                }
            }
            return null;
        }

        private static int number(Node node, String name) {
            return Integer.parseInt(attribute(node, name));
        }

        private static String attribute(Node node, String name) {
            return node.getAttributes().getNamedItem(name).getNodeValue();
        }
    }

    private static final class WindowsStyles {
        private static final int GWL_EXSTYLE = -20;
        private static final int WS_EX_TRANSPARENT = 0x20;
        private static final int WS_EX_LAYERED = 0x80000;
        private final Arena libraryArena = Arena.ofAuto();
        private final SymbolLookup user32 = SymbolLookup.libraryLookup(
                Path.of(System.getenv("SystemRoot"), "System32", "user32.dll"), libraryArena);
        private final MethodHandle findWindow = bind("FindWindowW",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        private final MethodHandle getStyle = bind("GetWindowLongW",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        private final MethodHandle setStyle = bind("SetWindowLongW",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT));

        private MethodHandle bind(String name, FunctionDescriptor descriptor) {
            return Linker.nativeLinker().downcallHandle(user32.find(name).orElseThrow(), descriptor);
        }

        private void setClickThrough(String title, boolean enabled) throws Throwable {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment name = arena.allocateArray(ValueLayout.JAVA_CHAR, title.length() + 1);
                for (int index = 0; index < title.length(); index++) {
                    name.setAtIndex(ValueLayout.JAVA_CHAR, index, title.charAt(index));
                }
                MemorySegment handle = (MemorySegment) findWindow.invokeExact(MemorySegment.NULL, name);
                if (handle.equals(MemorySegment.NULL)) {
                    throw new IOException("Overlay window was not found.");
                }
                int oldStyle = (int) getStyle.invokeExact(handle, GWL_EXSTYLE);
                int desired = enabled ? oldStyle | WS_EX_LAYERED | WS_EX_TRANSPARENT
                        : oldStyle & ~WS_EX_TRANSPARENT;
                setStyle.invoke(handle, GWL_EXSTYLE, desired);
                int actual = (int) getStyle.invokeExact(handle, GWL_EXSTYLE);
                if ((actual & WS_EX_TRANSPARENT) != (desired & WS_EX_TRANSPARENT)
                        || (enabled && (actual & WS_EX_LAYERED) == 0)) {
                    throw new IOException("Windows did not apply the requested window style.");
                }
            }
        }
    }
}
