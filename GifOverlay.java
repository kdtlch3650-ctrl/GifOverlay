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
    private static final int RESIZE_HANDLE_SIZE = 16;
    private final String windowTitle = "GifOverlay " + UUID.randomUUID();
    private final JFrame window = new JFrame(windowTitle);
    private final JFrame controls;
    private final GifOverlay owner;
    private final java.util.Map<Component, GifOverlay> overlays = new java.util.LinkedHashMap<>();
    private boolean disposed;

    public GifOverlay() {
        this(null);
    }

    private GifOverlay(GifOverlay owner) {
        this.owner = owner;
        controls = owner == null ? new JFrame("GifOverlay") : owner.controls;
    }
    private final Drawing drawing = new Drawing();
    private final Timer timer = new Timer(100, event -> advance());
    private final JButton openGif = new JButton("애니메이션 없음");
    private final JCheckBox sound = new JCheckBox("소리 재생");
    private final JCheckBox clickThrough = new JCheckBox("클릭 방지");
    private final JSpinner scale = new JSpinner(new SpinnerNumberModel(100, 10, 400, 5));
    private final JSlider volume = new JSlider(0, 100, 100);
    private final JLabel volumeLabel = new JLabel();
    private final Properties savedSettings = new Properties();
    private Path settingsFile;
    private final JButton removeGif = new JButton("제거");
    private final JButton openSound = new JButton("WAV 없음");
    private final JButton removeSound = new JButton("제거");
    private boolean bundled;
    private boolean overlayZip;
    private final JComboBox<Integer> resolution = new JComboBox<>(new Integer[]{100, 90, 80, 75, 70, 60, 50, 40, 30, 25});
    private File loadedAnimationFile;
    private final JCheckBox customDelay = new JCheckBox("ZIP 프레임 간격 고정");
    private final JSpinner delayMs = new JSpinner(new SpinnerNumberModel(40, 1, 60_000, 1));
    private TrayIcon tray;
    private Clip audio;
    private Animation animation;
    private int frameIndex;
    private boolean resizing;
    private WindowsStyles styles;

    public static void main(String[] args) {
        Locale.setDefault(Locale.Category.DISPLAY, Locale.KOREAN);
        JComponent.setDefaultLocale(Locale.KOREAN);
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
        installTray();
        JButton minimize = new JButton("트레이로 최소화");
        minimize.setToolTipText("재생을 계속하면서 설정 창을 트레이로 숨깁니다.");
        minimize.addActionListener(event -> controls.setVisible(false));
        JButton exit = new JButton("완전 종료");
        exit.setToolTipText("재생을 멈추고 GifOverlay를 종료합니다.");
        exit.addActionListener(event -> close());
        JPanel first = createOverlayPanel(minimize, exit);
        controls.setContentPane(bundled ? first : createTabs(first));
        controls.pack();
        controls.setLocationByPlatform(true);

        minimize.setEnabled(tray != null);
        controls.setDefaultCloseOperation(tray == null
                ? WindowConstants.EXIT_ON_CLOSE : WindowConstants.HIDE_ON_CLOSE);
        controls.setVisible(true);
        restoreMedia();
        overlays.values().stream().filter(overlay -> overlay != this).forEach(GifOverlay::restoreMedia);
    }

    private JTabbedPane tabs;

    private JPanel createTabs(JPanel first) {
        tabs = new JTabbedPane();
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        var addTab = new JPanel();
        tabs.addTab("+", null, addTab, "오버레이 탭 추가");
        int count = Math.clamp(savedInt("tabs.count", 1), 1, 100);
        installTab(first, this);
        for (int index = 1; index < count; index++) {
            addOverlayTab(index);
        }
        tabs.setSelectedIndex(Math.clamp(savedInt("tabs.selected", 0), 0, count - 1));
        tabs.addChangeListener(event -> {
            if (tabs.getSelectedComponent() == addTab) {
                SwingUtilities.invokeLater(() -> {
                    if (tabs.getSelectedComponent() != addTab) {
                        return;
                    }
                    if (overlays.size() >= 100) {
                        tabs.setSelectedIndex(overlays.size() - 1);
                        return;
                    }
                    tabs.setSelectedComponent(addOverlayTab(-1));
                    saveSettings();
                });
            } else {
                saveSettings();
            }
        });
        var content = new JPanel(new BorderLayout());
        content.add(tabs, BorderLayout.CENTER);
        return content;
    }

    private JPanel addOverlayTab(int restoredIndex) {
        var overlay = new GifOverlay(this);
        overlay.tray = tray;
        if (restoredIndex >= 0) {
            String prefix = "tabs." + restoredIndex + ".";
            for (String key : savedSettings.stringPropertyNames()) {
                if (key.startsWith(prefix)) {
                    overlay.savedSettings.setProperty(key.substring(prefix.length()), savedSettings.getProperty(key));
                }
            }
            overlay.applySavedControls();
        }
        overlay.savedSettings.putIfAbsent("tabName", "오버레이 " + (overlays.size() + 1));
        var minimize = new JButton("트레이로 최소화");
        minimize.setEnabled(tray != null);
        minimize.addActionListener(event -> controls.setVisible(false));
        var exit = new JButton("완전 종료");
        exit.addActionListener(event -> close());
        JPanel panel = overlay.createOverlayPanel(minimize, exit);
        installTab(panel, overlay);
        return panel;
    }

    private void installTab(JPanel panel, GifOverlay overlay) {
        String title = overlay.savedSettings.getProperty("tabName", "오버레이 1");
        int index = tabs.getTabCount() - 1;
        tabs.insertTab(title, null, panel, null, index);
        overlays.put(panel, overlay);
        var header = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        header.setOpaque(false);
        var label = new JLabel(title);
        label.setToolTipText("더블클릭하여 이름 변경");
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event)) {
                    return;
                }
                tabs.setSelectedComponent(panel);
                if (event.getClickCount() == 2) {
                    String name = JOptionPane.showInputDialog(controls, "오버레이 이름", label.getText());
                    if (name != null && !name.isBlank()) {
                        String trimmed = name.strip();
                        overlay.savedSettings.setProperty("tabName", trimmed);
                        label.setText(trimmed);
                        tabs.setTitleAt(tabs.indexOfComponent(panel), trimmed);
                        saveSettings();
                    }
                }
            }
        });
        header.add(label);
        if (overlay != this) {
            var remove = new JButton("−");
            remove.setPreferredSize(new Dimension(19, 19));
            remove.setBorder(BorderFactory.createEmptyBorder());
            remove.setOpaque(false);
            remove.setContentAreaFilled(false);
            remove.setForeground(new Color(73, 89, 112));
            remove.setRolloverEnabled(true);
            remove.getModel().addChangeListener(event -> {
                boolean hovered = remove.getModel().isRollover();
                remove.setContentAreaFilled(hovered);
                remove.setForeground(hovered ? new Color(158, 46, 58) : new Color(73, 89, 112));
            });
            remove.setToolTipText("이 오버레이 닫기");
            remove.getAccessibleContext().setAccessibleName("이 오버레이 닫기");
            remove.addActionListener(event -> {
                if (tabs.getSelectedComponent() == panel) {
                    tabs.setSelectedIndex(tabs.indexOfComponent(panel) - 1);
                }
                overlays.remove(panel);
                tabs.remove(panel);
                overlay.disposeOverlay();
                saveSettings();
            });
            header.add(remove);
        }
        tabs.setTabComponentAt(index, header);
    }


    private JPanel createOverlayPanel(JButton minimize, JButton exit) {
        window.setUndecorated(true);
        window.setBackground(new Color(0, 0, 0, 0));
        window.setAlwaysOnTop(true);
        window.setFocusableWindowState(false);
        window.add(drawing);
        timer.setRepeats(false);

        MouseAdapter drag = new MouseAdapter() {
            private Point offset;
            private Dimension startingSize;
            private Point startingCursor;

            @Override
            public void mouseEntered(MouseEvent event) {
                updateResizeFeedback(event);
            }

            @Override
            public void mouseMoved(MouseEvent event) {
                updateResizeFeedback(event);
            }

            @Override
            public void mouseExited(MouseEvent event) {
                if (!resizing) {
                    drawing.showResizeFeedback(false, false);
                }
            }

            @Override
            public void mousePressed(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event) || clickThrough.isSelected()) {
                    return;
                }
                if (!bundled && animation != null && drawing.isResizeHandle(event.getPoint())) {
                    resizing = true;
                    startingSize = drawing.getSize();
                    startingCursor = event.getLocationOnScreen();
                    drawing.showResizeFeedback(true, true);
                } else {
                    offset = event.getPoint();
                }
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (resizing && animation != null) {
                    Point cursor = event.getLocationOnScreen();
                    double width = startingSize.width + cursor.x - startingCursor.x;
                    double height = startingSize.height + cursor.y - startingCursor.y;
                    double originalWidth = animation.width();
                    double originalHeight = animation.height();
                    // 드래그 방향을 이미지의 대각선에 맞춰 원본 비율을 유지한다.
                    double factor = (width * originalWidth + height * originalHeight)
                            / (originalWidth * originalWidth + originalHeight * originalHeight);
                    scale.setValue(Math.clamp((int) Math.round(factor * 100), 10, 400));
                } else if (offset != null) {
                    Point cursor = event.getLocationOnScreen();
                    window.setLocation(cursor.x - offset.x, cursor.y - offset.y);
                }
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (offset != null || resizing) {
                    resizing = false;
                    saveSettings();
                }
                offset = null;
                updateResizeFeedback(event);
            }

            private void updateResizeFeedback(MouseEvent event) {
                boolean show = !bundled && animation != null && !clickThrough.isSelected()
                        && drawing.contains(event.getPoint());
                drawing.showResizeFeedback(show, show && drawing.isResizeHandle(event.getPoint()));
            }
        };
        drawing.addMouseListener(drag);
        drawing.addMouseMotionListener(drag);

        openGif.setToolTipText("GIF 또는 오버레이 ZIP을 선택합니다.");
        openSound.setToolTipText("재생할 WAV 파일을 선택합니다.");
        removeGif.setToolTipText("현재 애니메이션을 화면에서 제거합니다.");
        removeSound.setToolTipText("현재 소리를 멈추고 WAV 연결을 해제합니다.");
        sound.setToolTipText("선택한 WAV를 반복 재생합니다.");
        clickThrough.setToolTipText("오버레이 위의 클릭을 뒤에 있는 창으로 전달합니다.");
        scale.setToolTipText("원본 대비 표시 크기를 조절합니다. 작게 표시할 때는 프레임 해상도도 낮추면 처리 부담을 줄일 수 있습니다.");
        volume.setToolTipText("WAV 재생 볼륨을 조절합니다.");
        resolution.setToolTipText("오버레이 ZIP의 프레임 해상도를 설정합니다. 표시 크기가 작다면 해상도를 낮춰 처리 부담을 줄일 수 있습니다.");
        openGif.addActionListener(event -> chooseGif());
        openSound.addActionListener(event -> chooseSound());
        openGif.setHorizontalAlignment(SwingConstants.LEFT);
        openSound.setHorizontalAlignment(SwingConstants.LEFT);
        removeGif.setEnabled(false);
        removeSound.setEnabled(false);
        removeGif.addActionListener(event -> removeAnimation());
        removeSound.addActionListener(event -> {
            clearAudio();
            saveSettings();
        });
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
                drawing.showResizeFeedback(false, false);
                drawing.repaint();
                saveSettings();
            } catch (Throwable failure) {
                clickThrough.setSelected(!requested);
                showError("클릭 방지 설정을 바꾸지 못했습니다.", failure);
            }
        });

        JPanel settings = new JPanel(new GridLayout(0, 2, 8, 8));
        settings.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JPanel details = new JPanel(new GridLayout(0, 2, 12, 8));
        JButton library = new JButton("보관함…");
        library.setToolTipText("저장한 조합을 불러오거나 현재 조합을 저장합니다.");
        if (!bundled) {
            library.addActionListener(event -> {
                if (openGif.isEnabled()) {
                    showLibrary();
                }
            });
            customDelay.setToolTipText("선택 해제 시 delays.txt의 간격을 사용합니다.");
            delayMs.setToolTipText("프레임당 시간(밀리초)");
            delayMs.setEnabled(customDelay.isSelected());
            customDelay.addActionListener(event -> updateFrameTiming());
            delayMs.addChangeListener(event -> updateFrameTiming());
            JLabel quality = new JLabel("프레임 해상도 (%)");
            quality.setLabelFor(resolution);
            quality.setToolTipText(resolution.getToolTipText());
            details.add(quality);
            details.add(resolution);
            details.add(customDelay);
            details.add(delayMs);
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
        JLabel size = new JLabel("크기 (%)");
        size.setLabelFor(scale);
        size.setToolTipText(scale.getToolTipText());
        settings.add(size);
        settings.add(scale);
        volumeLabel.setLabelFor(volume);
        volumeLabel.setToolTipText(volume.getToolTipText());
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
        styleButton(exit, new Color(158, 46, 58));
        scale.addChangeListener(event -> {
            resizeOverlay();
            if (!resizing) {
                saveSettings();
            }
        });
        JLabel dropHint = new JLabel(bundled ? "Anoko — Papaya" : "GIF, 오버레이 ZIP 또는 WAV를 여기에 놓으세요.",
                SwingConstants.CENTER);
        dropHint.setBorder(BorderFactory.createEmptyBorder(12, 12, 0, 12));
        JPanel content = new JPanel(new BorderLayout());
        if (bundled) {
            settings.add(minimize);
            settings.add(exit);
            content.add(dropHint, BorderLayout.NORTH);
            content.add(settings, BorderLayout.CENTER);
        } else {
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            content.setBackground(new Color(243, 246, 251));
            content.setBorder(BorderFactory.createEmptyBorder(16, 16, 12, 16));
            JPanel files = new JPanel(new GridLayout(0, 1, 0, 8));
            files.add(mediaRow(openGif, removeGif));
            files.add(mediaRow(openSound, removeSound));
            dropHint.setHorizontalAlignment(SwingConstants.LEFT);
            dropHint.setBorder(null);
            dropHint.setForeground(new Color(73, 89, 112));
            files.add(dropHint);
            for (JButton button : List.of(openGif, openSound, library)) {
                styleButton(button, new Color(29, 78, 157));
            }
            for (JButton button : List.of(removeGif, removeSound)) {
                styleButton(button, new Color(158, 46, 58));
            }
            styleButton(minimize, new Color(51, 65, 85));
            settings.setBorder(null);
            content.add(settingsSection("미디어", files, library));
            content.add(Box.createVerticalStrut(10));
            content.add(settingsSection("재생", settings, null));
            content.add(Box.createVerticalStrut(10));
            content.add(settingsSection("애니메이션 설정", details, null));
            JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
            footer.setOpaque(false);
            footer.setAlignmentX(Component.LEFT_ALIGNMENT);
            footer.add(minimize);
            footer.add(exit);
            content.add(footer);
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
                        showError("끌어 놓은 파일을 읽지 못했습니다.", failure);
                        return false;
                    }
                }
            });
        }
        return content;
    }

    private void restoreMedia() {
        if (bundled) {
            loadBundledMedia();
        } else {
            File lastAnimation = recentFile("animationFile", openGif, "애니메이션");
            File lastSound = recentFile("soundFile", openSound, "WAV");
            if (lastAnimation != null) {
                loadGif(lastAnimation);
            }
            if (lastSound != null) {
                loadSound(lastSound);
            }
        }
    }

    private void styleButton(JButton button, Color ink) {
        button.setUI(new javax.swing.plaf.basic.BasicButtonUI() {
            @Override
            public void paint(Graphics graphics, JComponent component) {
                Graphics2D canvas = (Graphics2D) graphics.create();
                canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                ButtonModel model = button.getModel();
                Color fill = new Color(248, 250, 252);
                if (model.isEnabled()) {
                    if (model.isPressed() && model.isArmed()) {
                        fill = new Color(219, 232, 250);
                    } else if (model.isRollover()) {
                        fill = new Color(234, 242, 255);
                    }
                }
                canvas.setColor(fill);
                canvas.fillRoundRect(0, 0, button.getWidth(), button.getHeight(), 12, 12);
                canvas.setColor(button.hasFocus() ? new Color(36, 99, 187) : new Color(207, 218, 233));
                canvas.drawRoundRect(0, 0, button.getWidth() - 1, button.getHeight() - 1, 12, 12);
                canvas.dispose();
                super.paint(graphics, component);
            }
        });
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setRolloverEnabled(true);
        button.setForeground(ink);
        button.setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 12));
    }

    private JPanel settingsSection(String title, JPanel body, JButton action) {
        JPanel section = new JPanel(new BorderLayout(0, 10));
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.setBackground(Color.WHITE);
        section.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(216, 224, 235)),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        JPanel heading = new JPanel(new BorderLayout(8, 0));
        heading.setOpaque(false);
        JLabel label = new JLabel(title);
        label.setForeground(new Color(26, 44, 70));
        label.setFont(label.getFont().deriveFont(Font.BOLD, 14f));
        heading.add(label, BorderLayout.CENTER);
        if (action != null) {
            heading.add(action, BorderLayout.EAST);
        }
        body.setOpaque(false);
        section.add(heading, BorderLayout.NORTH);
        section.add(body, BorderLayout.CENTER);
        return section;
    }

    private JPanel mediaRow(JButton file, JButton remove) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.add(file, BorderLayout.CENTER);
        row.add(remove, BorderLayout.EAST);
        return row;
    }

    private void removeAnimation() {
        timer.stop();
        resizing = false;
        drawing.showResizeFeedback(false, false);
        window.setVisible(false);
        animation = null;
        loadedAnimationFile = null;
        overlayZip = false;
        frameIndex = 0;
        clickThrough.setEnabled(false);
        savedSettings.remove("animationFile");
        openGif.setText("애니메이션 없음");
        openGif.setToolTipText("GIF 또는 오버레이 ZIP을 선택합니다.");
        removeGif.setEnabled(false);
        saveSettings();
    }

    private void clearAudio() {
        if (audio != null) {
            audio.close();
        }
        audio = null;
        sound.setSelected(false);
        sound.setEnabled(false);
        volume.setEnabled(true);
        savedSettings.remove("soundFile");
        openSound.setText("WAV 없음");
        openSound.setToolTipText("재생할 WAV 파일을 선택합니다.");
        removeSound.setEnabled(false);
    }

    private File recentFile(String key, JButton label, String type) {
        String path = savedSettings.getProperty(key);
        if (path == null || path.isBlank()) {
            return null;
        }
        (label == openGif ? removeGif : removeSound).setEnabled(true);
        File file = new File(path);
        if (file.isFile()) {
            showMediaFile(label, type, file, "");
            return file;
        }
        showMediaFile(label, type, file, " (파일 없음)");
        return null;
    }

    private void showMediaFile(JButton label, String type, File file, String status) {
        String name = file.getName();
        if (name.length() > 38) {
            name = name.substring(0, 24) + "…" + name.substring(name.length() - 12);
        }
        label.setText(type + ": " + name + status);
        label.setToolTipText("눌러서 파일 변경 · " + file.getAbsolutePath());
    }

    private void rememberMedia(String key, JButton label, String type, File file) {
        if (!bundled) {
            showMediaFile(label, type, file, "");
            savedSettings.setProperty(key, file.getAbsolutePath());
            saveSettings();
        }
    }

    private void showLibrary() {
        GifOverlay libraryOwner = owner == null ? this : owner;
        Properties presets = libraryOwner.savedSettings;
        var model = new DefaultListModel<String>();
        presets.stringPropertyNames().stream()
                .filter(key -> key.startsWith("preset.") && key.endsWith(".name"))
                .sorted(java.util.Comparator.comparing(presets::getProperty))
                .forEach(model::addElement);
        var list = new JList<>(model);
        var previews = new java.util.HashMap<String, LibraryPreview>();
        var previewTasks = new java.util.HashMap<String, SwingWorker<LibraryPreview, Void>>();
        var dialog = new JDialog(controls, "보관함", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent event) {
                var tasks = List.copyOf(previewTasks.values());
                previewTasks.clear();
                tasks.forEach(task -> task.cancel(true));
            }
        });
        java.util.function.Consumer<String> refreshPreview = key -> {
            String prefix = key.substring(0, key.length() - 4);
            String path = presets.getProperty(prefix + "animationFile", "");
            previews.remove(key);
            list.repaint();
            var task = new SwingWorker<LibraryPreview, Void>() {
                @Override
                protected LibraryPreview doInBackground() {
                    return readLibraryPreview(new File(path));
                }

                @Override
                protected void done() {
                    if (previewTasks.get(key) != this) {
                        return;
                    }
                    previewTasks.remove(key);
                    try {
                        previews.put(key, get());
                        list.repaint();
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                    } catch (java.util.concurrent.ExecutionException failure) {
                        previews.put(key, new LibraryPreview(null, "미리보기 없음"));
                        list.repaint();
                    }
                }
            };
            var previous = previewTasks.put(key, task);
            if (previous != null) {
                previous.cancel(true);
            }
            task.execute();
        };
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(5);
        list.setFixedCellHeight(72);
        var renderer = new DefaultListCellRenderer();
        list.setCellRenderer((view, key, index, selected, focused) -> {
            String prefix = key.substring(0, key.length() - 4);
            var preview = previews.get(key);
            String status = preview == null ? "불러오는 중" : preview.status();
            String name = presets.getProperty(key);
            renderer.getListCellRendererComponent(view,
                    status.isEmpty() ? name : name + " · " + status, index, selected, focused);
            boolean hasWav = !presets.getProperty(prefix + "soundFile", "").isBlank();
            renderer.setIcon(new LibraryPreviewIcon(preview == null ? null : preview.image(), hasWav));
            renderer.setIconTextGap(10);
            return renderer;
        });
        model.elements().asIterator().forEachRemaining(refreshPreview);
        var buttons = new JPanel();
        JButton load = new JButton("불러오기");
        load.addActionListener(event -> {
            String key = list.getSelectedValue();
            if (key != null) {
                String prefix = key.substring(0, key.length() - 4);
                File image = new File(presets.getProperty(prefix + "animationFile", ""));
                String wav = presets.getProperty(prefix + "soundFile", "");
                File soundPath = wav.isEmpty() ? null : new File(wav);
                if (!image.isFile() || (soundPath != null && !soundPath.isFile())) {
                    JOptionPane.showMessageDialog(dialog, "저장한 파일을 찾을 수 없습니다. 경로 변경에서 다시 지정하세요.");
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
        JButton add = new JButton("현재 조합 저장");
        add.setEnabled(animation != null);
        add.addActionListener(event -> {
            String name = JOptionPane.showInputDialog(dialog, "조합 이름");
            if (name == null || name.isBlank()) {
                return;
            }
            saveSettings();
            String prefix = "preset." + UUID.randomUUID() + ".";
            for (String key : List.of("animationFile", "soundFile", "x", "y", "scale", "volume",
                    "sound", "resolution", "customDelay", "delayMs")) {
                String value = savedSettings.getProperty(key);
                if (value != null && (!key.equals("soundFile") || audio != null)) {
                    presets.setProperty(prefix + key, value);
                }
            }
            presets.setProperty(prefix + "name", name.strip());
            model.addElement(prefix + "name");
            refreshPreview.accept(prefix + "name");
            list.setSelectedIndex(model.size() - 1);
            libraryOwner.saveSettings();
        });
        JButton rename = new JButton("이름 변경");
        rename.addActionListener(event -> {
            String key = list.getSelectedValue();
            if (key != null) {
                String name = JOptionPane.showInputDialog(dialog, "조합 이름", presets.getProperty(key));
                if (name != null && !name.isBlank()) {
                    presets.setProperty(key, name.strip());
                    list.repaint();
                    libraryOwner.saveSettings();
                }
            }
        });
        JButton relink = new JButton("경로 변경");
        relink.addActionListener(event -> {
            String key = list.getSelectedValue();
            if (key == null) {
                return;
            }
            Object[] options = {"애니메이션", "WAV", "WAV 없음"};
            int choice = JOptionPane.showOptionDialog(dialog, "경로를 변경할 파일을 선택하세요.", "경로 변경",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
            String prefix = key.substring(0, key.length() - 4);
            if (choice == 2) {
                presets.remove(prefix + "soundFile");
            } else if (choice >= 0) {
                File file = choice == 0 ? chooseFile(dialog, "GIF 또는 오버레이 ZIP", "gif", "zip")
                        : chooseFile(dialog, "WAV 오디오", "wav");
                if (file == null) {
                    return;
                }
                presets.setProperty(prefix + (choice == 0 ? "animationFile" : "soundFile"),
                        file.getAbsolutePath());
            } else {
                return;
            }
            if (choice == 0) {
                refreshPreview.accept(key);
            }
            list.repaint();
            libraryOwner.saveSettings();
        });
        JButton delete = new JButton("삭제");
        load.setToolTipText("선택한 조합을 현재 오버레이에 적용합니다.");
        add.setToolTipText("현재 파일과 재생 설정을 새 조합으로 저장합니다.");
        rename.setToolTipText("선택한 조합의 이름을 바꿉니다.");
        relink.setToolTipText("선택한 조합의 파일 경로를 바꾸거나 WAV 연결을 해제합니다.");
        delete.setToolTipText("선택한 조합을 보관함에서 삭제합니다.");
        delete.addActionListener(event -> {
            String key = list.getSelectedValue();
            if (key != null && JOptionPane.showConfirmDialog(dialog, "저장한 조합을 삭제할까요?",
                    "보관함", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                String prefix = key.substring(0, key.length() - 4);
                presets.keySet().removeIf(item -> item.toString().startsWith(prefix));
                model.removeElement(key);
                previews.remove(key);
                var task = previewTasks.remove(key);
                if (task != null) {
                    task.cancel(true);
                }
                libraryOwner.saveSettings();
            }
        });
        for (JButton button : List.of(load, add, rename, relink, delete)) {
            Color ink = new Color(51, 65, 85);
            if (button == load || button == add) {
                ink = new Color(29, 78, 157);
            } else if (button == delete) {
                ink = new Color(158, 46, 58);
            }
            styleButton(button, ink);
            buttons.add(button);
        }
        dialog.add(new JScrollPane(list), BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.getRootPane().setDefaultButton(load);
        dialog.pack();
        dialog.setLocationRelativeTo(controls);
        dialog.setVisible(true);
    }

    private record LibraryPreview(BufferedImage image, String status) {}

    private static LibraryPreview readLibraryPreview(File file) {
        if (!file.isFile()) {
            return new LibraryPreview(null, "파일 없음");
        }
        try {
            BufferedImage image;
            if (file.getName().toLowerCase(Locale.ROOT).endsWith(".overlay.zip")) {
                try (ZipFile zip = new ZipFile(file)) {
                    var frame = zip.getEntry("frames/0.png");
                    if (frame == null) {
                        return new LibraryPreview(null, "미리보기 없음");
                    }
                    try (var input = zip.getInputStream(frame);
                         var imageInput = ImageIO.createImageInputStream(input)) {
                        image = readPreviewFrame(imageInput);
                    }
                }
            } else {
                try (var input = ImageIO.createImageInputStream(file)) {
                    image = readPreviewFrame(input);
                }
            }
            return new LibraryPreview(image, image == null ? "미리보기 없음" : "");
        } catch (IOException | IllegalArgumentException failure) {
            return new LibraryPreview(null, "미리보기 없음");
        }
    }

    private static BufferedImage readPreviewFrame(ImageInputStream input) throws IOException {
        if (input == null) {
            return null;
        }
        var readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) {
            return null;
        }
        var reader = readers.next();
        try {
            reader.setInput(input, true, true);
            var parameters = reader.getDefaultReadParam();
            int step = Math.max(1, Math.max(reader.getWidth(0), reader.getHeight(0)) / 64);
            parameters.setSourceSubsampling(step, step, 0, 0);
            return reader.read(0, parameters);
        } finally {
            reader.dispose();
        }
    }

    private record LibraryPreviewIcon(BufferedImage image, boolean hasWav) implements Icon {
        @Override
        public int getIconWidth() {
            return 80;
        }

        @Override
        public int getIconHeight() {
            return 60;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D canvas = (Graphics2D) graphics.create();
            canvas.setColor(new Color(235, 240, 248));
            canvas.fillRoundRect(x, y, getIconWidth(), getIconHeight(), 8, 8);
            if (image != null) {
                double scale = Math.min((double) getIconWidth() / image.getWidth(),
                        (double) getIconHeight() / image.getHeight());
                int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
                int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
                canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                canvas.drawImage(image, x + (getIconWidth() - width) / 2,
                        y + (getIconHeight() - height) / 2, width, height, null);
            }
            if (hasWav) {
                int badgeX = x + getIconWidth() - 23;
                canvas.setColor(new Color(29, 78, 157));
                canvas.fillRoundRect(badgeX, y + 3, 20, 20, 6, 6);
                canvas.setColor(Color.WHITE);
                canvas.drawString("♪", badgeX + 6, y + 18);
            }
            canvas.dispose();
        }
    }

    private void restoreSettings() {
        if (Files.isRegularFile(settingsFile)) {
            try (var input = Files.newInputStream(settingsFile)) {
                savedSettings.load(input);
            } catch (IOException | IllegalArgumentException failure) {
                savedSettings.clear();
            }
        }
        applySavedControls();
    }

    private void applySavedControls() {
        scale.setValue(Math.clamp(savedInt("scale", 100), 10, 400));
        volume.setValue(Math.clamp(savedInt("volume", 100), 0, 100));
        sound.setSelected(Boolean.parseBoolean(savedSettings.getProperty("sound", "false")));
        resolution.setSelectedItem(savedInt("resolution", 100));
        customDelay.setSelected(Boolean.parseBoolean(savedSettings.getProperty("customDelay", "false")));
        delayMs.setValue(Math.clamp(savedInt("delayMs", 40), 1, 60_000));
    }

    private int savedInt(String key, int fallback) {
        try {
            return Integer.parseInt(setting(key));
        } catch (NumberFormatException failure) {
            return fallback;
        }
    }

    private String setting(String key) {
        Properties source = owner != null && key.startsWith("preset.") ? owner.savedSettings : savedSettings;
        return source.getProperty(key);
    }

    private void saveSettings() {
        if (disposed) {
            return;
        }
        savedSettings.setProperty("scale", scale.getValue().toString());
        savedSettings.setProperty("volume", Integer.toString(volume.getValue()));
        savedSettings.setProperty("sound", Boolean.toString(sound.isSelected()));
        if (!bundled) {
            savedSettings.setProperty("resolution", resolution.getSelectedItem().toString());
            savedSettings.setProperty("customDelay", Boolean.toString(customDelay.isSelected()));
            savedSettings.setProperty("delayMs", delayMs.getValue().toString());
            if (window.isVisible()) {
                savedSettings.setProperty("clickThrough", Boolean.toString(clickThrough.isSelected()));
            }
        }
        if (window.isVisible()) {
            savedSettings.setProperty("x", Integer.toString(window.getX()));
            savedSettings.setProperty("y", Integer.toString(window.getY()));
        }
        GifOverlay root = owner == null ? this : owner;
        root.writeSettings();
    }

    private void writeSettings() {
        if (settingsFile == null) {
            return;
        }
        if (tabs != null) {
            savedSettings.keySet().removeIf(key -> key.toString().startsWith("tabs."));
            savedSettings.setProperty("tabs.count", Integer.toString(overlays.size()));
            savedSettings.setProperty("tabs.selected",
                    Integer.toString(Math.min(tabs.getSelectedIndex(), overlays.size() - 1)));
            int index = 0;
            for (GifOverlay overlay : overlays.values()) {
                if (index != 0) {
                    String prefix = "tabs." + index + ".";
                    overlay.savedSettings.forEach((key, value) -> savedSettings.put(prefix + key, value));
                }
                index++;
            }
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
            System.err.println("오버레이 설정을 저장하지 못했습니다. " + failure.getMessage());
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
        volumeLabel.setText("볼륨 (" + volume.getValue() + "%)");
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
            showError("Anoko 번들 파일을 찾을 수 없습니다.", failure);
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
        MenuItem settings = new MenuItem("설정");
        settings.addActionListener(event -> SwingUtilities.invokeLater(this::showControls));
        menu.add(settings);
        MenuItem exit = new MenuItem("완전 종료");
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
        File selected = chooseFile("GIF 또는 오버레이 ZIP", "gif", "zip");
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
            showError("지원하는 파일이 없습니다.", new IOException("GIF, 오버레이 ZIP 또는 WAV 파일을 놓으세요."));
            return false;
        }
        if ((gif != null && !openGif.isEnabled()) || (wav != null && !openSound.isEnabled())) {
            showError("파일을 불러오는 중입니다.", new IOException("불러오기가 끝난 뒤 파일을 다시 놓으세요."));
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
        removeGif.setEnabled(false);
        if (preset != null) {
            removeSound.setEnabled(false);
            openSound.setEnabled(false);
        }
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
                    if (disposed) {
                        return;
                    }
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
                        if (clickThrough.isEnabled() && !clickThrough.isSelected()
                                && Boolean.parseBoolean(savedSettings.getProperty("clickThrough"))) {
                            clickThrough.doClick(0);
                        }
                    } else {
                        resizeOverlay();
                    }
                    displayFrame();
                    rememberMedia("animationFile", openGif, "애니메이션", selected);
                } catch (ExecutionException failure) {
                    if (!bundled && animation == null) {
                        showMediaFile(openGif, "애니메이션", selected, " (불러오기 실패)");
                    }
                    showError("애니메이션을 열지 못했습니다.", failure.getCause());
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                } finally {
                    if (preparedSound != null) {
                        preparedSound.close();
                    }
                    openGif.setEnabled(true);
                    removeGif.setEnabled(!openGif.getText().equals("애니메이션 없음"));
                    openSound.setEnabled(true);
                    removeSound.setEnabled(!openSound.getText().equals("WAV 없음"));
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
        customDelay.setSelected(Boolean.parseBoolean(setting(prefix + "customDelay")));
        delayMs.setValue(savedInt(prefix + "delayMs", 40));
        updateFrameTiming();
        for (String axis : List.of("x", "y")) {
            savedSettings.setProperty(axis, Integer.toString(savedInt(prefix + axis, Integer.MIN_VALUE)));
        }
        audio = replacement;
        sound.setEnabled(audio != null);
        sound.setSelected(audio != null && Boolean.parseBoolean(setting(prefix + "sound")));
        volume.setEnabled(audio == null || applyVolume(audio));
        if (wav == null) {
            clearAudio();
        } else {
            savedSettings.setProperty("soundFile", wav.getAbsolutePath());
            showMediaFile(openSound, "WAV", wav, "");
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
        File selected = chooseFile("WAV 오디오", "wav");
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
            rememberMedia("soundFile", openSound, "WAV", selected);
            removeSound.setEnabled(true);
        } catch (Exception failure) {
            if (!bundled && audio == null) {
                showMediaFile(openSound, "WAV", selected, " (불러오기 실패)");
                removeSound.setEnabled(true);
            }
            if (replacement != null) {
                replacement.close();
            }
            showError("WAV를 열지 못했습니다.", failure);
        }
    }

    private void showError(String message, Throwable failure) {
        JOptionPane.showMessageDialog(controls, message + "\n" + failure.getMessage(),
                "GifOverlay", JOptionPane.ERROR_MESSAGE);
    }

    private void close() {
        saveSettings();
        if (bundled) {
            disposeOverlay();
        } else {
            overlays.values().forEach(GifOverlay::disposeOverlay);
        }
        if (tray != null) {
            SystemTray.getSystemTray().remove(tray);
        }
        controls.dispose();
        System.exit(0);
    }

    private void disposeOverlay() {
        disposed = true;
        timer.stop();
        if (audio != null) {
            audio.close();
        }
        window.dispose();
    }

    private final class Drawing extends JComponent {
        private boolean showBorder;

        private boolean isResizeHandle(Point point) {
            return contains(point) && point.x >= getWidth() - Math.max(1, Math.min(RESIZE_HANDLE_SIZE, getWidth() / 2))
                    && point.y >= getHeight() - Math.max(1, Math.min(RESIZE_HANDLE_SIZE, getHeight() / 2));
        }

        private void showResizeFeedback(boolean show, boolean overHandle) {
            setCursor(Cursor.getPredefinedCursor(overHandle ? Cursor.SE_RESIZE_CURSOR : Cursor.DEFAULT_CURSOR));
            if (showBorder != show) {
                showBorder = show;
                repaint();
            }
        }

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
                if (!bundled && !clickThrough.isSelected()) {
                    if (showBorder) {
                        canvas.setColor(Color.BLACK);
                        canvas.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
                        canvas.setColor(Color.WHITE);
                        canvas.drawRect(1, 1, getWidth() - 3, getHeight() - 3);
                    }
                    // 투명한 모서리에서도 손잡이가 마우스 입력을 받도록 항상 그린다.
                    int grip = Math.max(1, Math.min(RESIZE_HANDLE_SIZE, Math.min(getWidth(), getHeight()) / 2));
                    int x = getWidth() - grip;
                    int y = getHeight() - grip;
                    canvas.setColor(new Color(37, 87, 172));
                    canvas.fillRect(x, y, grip, grip);
                    canvas.setColor(Color.BLACK);
                    canvas.drawRect(x, y, grip - 1, grip - 1);
                    canvas.setColor(Color.WHITE);
                    if (grip >= 8) {
                        canvas.drawLine(x + 3, y + grip - 4, x + grip - 4, y + 3);
                    }
                }
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
                    throw new IOException("파일을 읽을 수 없습니다. " + file.getName());
                }
                reader.setInput(input);
                Node stream = reader.getStreamMetadata().getAsTree("javax_imageio_gif_stream_1.0");
                Node screen = child(stream, "LogicalScreenDescriptor");
                int width = number(screen, "logicalScreenWidth");
                int height = number(screen, "logicalScreenHeight");
                int count = reader.getNumImages(true);
                if (width <= 0 || height <= 0 || count <= 0
                        || (long) width * height > 120_000_000L / count) {
                    throw new IOException("GIF가 비어 있거나 너무 큽니다.");
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
                String[] delays = null;
                if (timing != null) {
                    try (var input = zip.getInputStream(timing)) {
                        byte[] bytes = input.readNBytes(8193);
                        if (bytes.length > 8192) {
                            throw new IOException("오버레이 프레임 간격 파일이 너무 큽니다.");
                        }
                        delays = new String(bytes, java.nio.charset.StandardCharsets.US_ASCII)
                                .strip().split("\\s+");
                    }
                }
                List<GifFrame> frames = new ArrayList<>();
                long duration = 0;
                long pixels = 0;
                int width = 0;
                int height = 0;
                for (int index = 0; delays == null || index < delays.length; index++) {
                    var entry = zip.getEntry("frames/" + index + ".png");
                    if (delays == null && entry == null) {
                        break;
                    }
                    int delay = 100;
                    try {
                        if (delays != null) {
                            delay = Integer.parseInt(delays[index]);
                        }
                    } catch (NumberFormatException failure) {
                        throw new IOException("오버레이 프레임 간격이 올바르지 않습니다.", failure);
                    }
                    if (delay <= 0 || delay > 60_000 || entry == null || entry.getSize() > 8_000_000) {
                        throw new IOException("올바르지 않은 오버레이 프레임 번호: " + index + ".");
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
                            throw new IOException("오버레이 프레임이 너무 크거나 프레임 크기가 서로 다릅니다.");
                        }
                        frames.add(new GifFrame(scaledFrame(reader.read(0), percent, false), delay));
                    } finally {
                        reader.dispose();
                    }
                    duration += delay * 1_000L;
                }
                if (frames.isEmpty()) {
                    throw new IOException("첫 프레임 frames/0.png를 찾을 수 없습니다.");
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
                    throw new IOException("오버레이 창을 찾을 수 없습니다.");
                }
                int oldStyle = (int) getStyle.invokeExact(handle, GWL_EXSTYLE);
                int desired = enabled ? oldStyle | WS_EX_LAYERED | WS_EX_TRANSPARENT
                        : oldStyle & ~WS_EX_TRANSPARENT;
                setStyle.invoke(handle, GWL_EXSTYLE, desired);
                int actual = (int) getStyle.invokeExact(handle, GWL_EXSTYLE);
                if ((actual & WS_EX_TRANSPARENT) != (desired & WS_EX_TRANSPARENT)
                        || (enabled && (actual & WS_EX_LAYERED) == 0)) {
                    throw new IOException("Windows에서 요청한 창 설정을 적용하지 못했습니다.");
                }
            }
        }
    }
}
