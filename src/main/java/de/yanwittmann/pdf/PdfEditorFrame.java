package de.yanwittmann.pdf;

import jnafilechooser.api.WindowsFileChooser;
import org.apache.pdfbox.io.RandomAccessFile;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class PdfEditorFrame extends JFrame {

    private final static int PDF_PREVIEW_WIDTH = 300;

    private final PDDocument pdDocument;
    private final File sourceFile;

    private final JPanel pdfPagesPanel;

    private boolean actionInProgress = false;

    public PdfEditorFrame() throws IOException {
        this(null);
    }

    public PdfEditorFrame(File sourceFile) throws IOException {
        super("PDF Editor");
        this.sourceFile = sourceFile;

        if (this.sourceFile != null) {
            final PDFParser parser = new PDFParser(new RandomAccessFile(this.sourceFile, "r"));
            parser.parse();
            this.pdDocument = parser.getPDDocument();
        } else {
            this.pdDocument = new PDDocument();
        }

        // a frame with all pages of the pdf document
        pdfPagesPanel = new JPanel();
        setGridColumns(3);
        final ScrollPane pdfPanelScrollPane = new ScrollPane();
        pdfPanelScrollPane.add(pdfPagesPanel);
        this.getContentPane().add(pdfPanelScrollPane);

        pdfPanelScrollPane.getVAdjustable().setUnitIncrement(16);

        buildPagePreview();

        new DropTarget(this, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent dtde) {
                try {
                    if (actionInProgress) return;
                    actionInProgress = true;
                    dtde.acceptDrop(DnDConstants.ACTION_COPY_OR_MOVE);
                    final List<File> files = (List<File>) dtde.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    files.forEach(file -> appendPdfFromFile(file));
                } catch (UnsupportedFlavorException | IOException e) {
                    e.printStackTrace();
                } finally {
                    actionInProgress = false;
                }
            }
        });

        this.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                int newWidth = e.getComponent().getWidth();
                int cols = (int) Math.floor(newWidth / (double) PDF_PREVIEW_WIDTH);
                setGridColumns(cols);
            }
        });

        addCommandKeyAction(KeyEvent.VK_S, this::savePdf);
        addCommandKeyAction(KeyEvent.VK_A, () -> {
            for (Component component : pdfPagesPanel.getComponents()) {
                if (component instanceof PdfPagePanel) {
                    ((PdfPagePanel) component).setSelected(true);
                }
            }
        });
        addCommandKeyAction(KeyEvent.VK_I, () -> {
            for (Component component : pdfPagesPanel.getComponents()) {
                if (component instanceof PdfPagePanel) {
                    ((PdfPagePanel) component).toggleSelected();
                }
            }
        });

        this.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        this.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (pdDocument.getNumberOfPages() > 0) {
                    int result = JOptionPane.showConfirmDialog(PdfEditorFrame.this, "Do you want to reset the session and load a new PDF?\nUse 'Cancel' to close the window.", "Closing action", JOptionPane.OK_CANCEL_OPTION);
                    if (result == JOptionPane.YES_OPTION) {
                        try {
                            new PdfEditorFrame();
                            this.windowClosed(e);
                            dispose();
                        } catch (IOException ignored) {
                        }
                    } else if (result == JOptionPane.CANCEL_OPTION) {
                        System.exit(0);
                    }
                } else {
                    System.exit(0);
                }
            }
        });

        this.setIconImage(new ImageIcon(getClass().getResource("/pdf.png")).getImage());

        this.setSize(PDF_PREVIEW_WIDTH * 3 + 60, 700);
        this.setLocationRelativeTo(null);
        this.setVisible(true);
    }

    private void setGridColumns(int columns) {
        if (columns == 0) return;
        pdfPagesPanel.setLayout(new GridLayout(0, columns));
    }

    private final AtomicInteger lastSelectedPage = new AtomicInteger(0);

    private void buildPagePreview() {
        // remove all previous pages
        pdfPagesPanel.removeAll();

        new Thread(() -> {
            final JLabel loadingLabel = new JLabel("Loading...");
            loadingLabel.setHorizontalAlignment(SwingConstants.CENTER);
            pdfPagesPanel.add(loadingLabel);
            refresh();

            // add all pages to the frame
            final PDFRenderer pdfRenderer = new PDFRenderer(pdDocument);
            for (int i = 0; i < pdDocument.getNumberOfPages(); i++) {
                int finalI = i;

                final PDPage page = pdDocument.getPage(i);
                final PdfPagePanel pdfPage = new PdfPagePanel(pdfRenderer, i, page);

                pdfPage.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        if (e.getButton() == MouseEvent.BUTTON1) { // left click
                            if (e.isShiftDown()) {
                                final int first = Math.min(lastSelectedPage.get(), finalI);
                                final int last = Math.max(lastSelectedPage.get(), finalI);
                                for (int j = first; j <= last; j++) {
                                    final PdfPagePanel pdfPagePanel = (PdfPagePanel) pdfPagesPanel.getComponent(j);
                                    pdfPagePanel.setSelected(true);
                                }
                            } else {
                                pdfPage.setSelected(!pdfPage.isSelected());
                            }
                            lastSelectedPage.set(finalI);
                        } else if (e.getButton() == MouseEvent.BUTTON3) { // right click
                            if (actionInProgress) {
                                System.out.println("Action in progress");
                                return;
                            }
                            actionInProgress = true;
                            try {
                                interactionMenu();
                            } catch (Exception ex) {
                                throw new RuntimeException(ex);
                            } finally {
                                actionInProgress = false;
                            }
                        }
                    }
                });
                pdfPagesPanel.add(pdfPage);

                refresh();
            }

            pdfPagesPanel.remove(loadingLabel);

            refresh();
        }).start();
    }

    private void refresh() {
        pdfPagesPanel.revalidate();
        pdfPagesPanel.repaint();
    }

    private void addCommandKeyAction(int keyCode, Runnable action) {
        final String actionName = "action" + keyCode;
        final Action actionObject = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        };
        this.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(keyCode, Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()), actionName);
        this.getRootPane().getActionMap().put(actionName, actionObject);
    }

    private File pickFileSave() {
        final WindowsFileChooser fc;

        if (sourceFile != null) {
            fc = new WindowsFileChooser(sourceFile.getParentFile());
            fc.setDefaultFilename(sourceFile.getName());
        } else {
            fc = new WindowsFileChooser();
        }

        fc.setTitle("PDF Pick File");
        fc.addFilter("pdf", "pdf");

        fc.showSaveDialog(this);

        return fc.getSelectedFile();
    }

    private static File pickFileOpen(File dir) {
        final WindowsFileChooser fc = new WindowsFileChooser(dir);

        fc.setTitle("PDF Pick File");
        fc.addFilter("pdf", "pdf");

        fc.showOpenDialog(null);

        return fc.getSelectedFile();
    }

    private void savePdf() {
        try {
            File file = pickFileSave();
            if (file != null) {
                pdDocument.save(file);
            }
        } catch (IOException ioException) {
            ioException.printStackTrace();
            JOptionPane.showMessageDialog(this, "Error saving file: " + ioException.getMessage());
        }
    }

    private void interactionMenu() {
        // check how many pages are selected
        final List<PdfPagePanel> selectedPanels = getSelectedPanels();

        final List<JMenuItem> menuItems = new ArrayList<>();


        if (selectedPanels.size() == 0) {
            menuItems.add(createActionMenuItem("Save", (e) -> {
                savePdf();
            }));
        }

        if (selectedPanels.size() > 0) {
            menuItems.add(createActionMenuItem("Delete page" + (selectedPanels.size() > 1 ? "s" : ""), (e) -> {
                for (PdfPagePanel selectedPanel : selectedPanels) {
                    pdDocument.removePage(selectedPanel.getPageIndex());
                }
                buildPagePreview();
            }));
        }

        if (selectedPanels.size() == 2) {
            menuItems.add(createActionMenuItem("Swap pages", (e) -> {
                final PdfPagePanel firstPanel = selectedPanels.get(0);
                final PdfPagePanel secondPanel = selectedPanels.get(1);

                ((PageRearrangement) (index) -> {
                    if (index == firstPanel.getPageIndex()) return secondPanel.getPage();
                    else if (index == secondPanel.getPageIndex()) return firstPanel.getPage();
                    else return pdDocument.getPage(index);
                }).rearrangePages(pdDocument);

                buildPagePreview();
            }));
        }

        if (selectedPanels.size() == 2) {
            menuItems.add(createActionMenuItem("Insert second before first", (e) -> {
                final PdfPagePanel firstPanel = selectedPanels.get(0);
                final PdfPagePanel secondPanel = selectedPanels.get(1);

                ((PageRearrangementRaw) (pdDocument, rearrangedPages) -> {
                    for (int i = 0; i < pdDocument.getNumberOfPages(); i++) {
                        if (i == firstPanel.getPageIndex()) {
                            rearrangedPages.add(secondPanel.getPage());
                            rearrangedPages.add(firstPanel.getPage());
                        } else if (i != secondPanel.getPageIndex()) {
                            rearrangedPages.add(pdDocument.getPage(i));
                        }
                    }
                }).rearrangePages(pdDocument);

                buildPagePreview();
            }));
        }

        if (selectedPanels.size() == 2) {
            menuItems.add(createActionMenuItem("Insert first after second", (e) -> {
                final PdfPagePanel firstPanel = selectedPanels.get(0);
                final PdfPagePanel secondPanel = selectedPanels.get(1);

                ((PageRearrangementRaw) (pdDocument, rearrangedPages) -> {
                    for (int i = 0; i < pdDocument.getNumberOfPages(); i++) {
                        if (i == secondPanel.getPageIndex()) {
                            rearrangedPages.add(secondPanel.getPage());
                            rearrangedPages.add(firstPanel.getPage());
                        } else if (i != firstPanel.getPageIndex()) {
                            rearrangedPages.add(pdDocument.getPage(i));
                        }
                    }
                }).rearrangePages(pdDocument);

                buildPagePreview();
            }));
        }

        if (selectedPanels.size() > 0) {
            menuItems.add(createActionMenuItem("Rotate 90°", (e) -> {
                for (PdfPagePanel panel : selectedPanels) {
                    final PDPage page = panel.getPage();
                    page.setRotation(page.getRotation() + 90);
                    cachedThumbnails.remove(page);
                }

                buildPagePreview();
            }));
        }

        menuItems.add(createActionMenuItem("Insert from file at end of document", (e) -> {
            final File file = pickFileOpen(sourceFile != null ? sourceFile.getParentFile() : null);
            appendPdfFromFile(file);
        }));


        final JPopupMenu popupMenu = new JPopupMenu();
        for (JMenuItem menuItem : menuItems) {
            popupMenu.add(menuItem);
        }

        final int mouseX = MouseInfo.getPointerInfo().getLocation().x;
        final int mouseY = MouseInfo.getPointerInfo().getLocation().y;

        final int x = mouseX - this.getLocationOnScreen().x;
        final int y = mouseY - this.getLocationOnScreen().y;

        popupMenu.show(this, x, y);

        refresh();
    }

    private void appendPdfFromFile(File file) {
        if (file != null && file.getName().endsWith(".pdf")) {
            try (final PDDocument insertDocument = PDDocument.load(file)) {
                new PDFMergerUtility().appendDocument(pdDocument, insertDocument);
            } catch (IOException ioException) {
                ioException.printStackTrace();
                JOptionPane.showMessageDialog(this, "Error loading file: " + ioException.getMessage());
            }
        }

        buildPagePreview();
    }

    private List<PdfPagePanel> getSelectedPanels() {
        return Arrays.stream(pdfPagesPanel.getComponents())
                .filter(pageComponent -> pageComponent instanceof PdfPagePanel)
                .map(pageComponent -> (PdfPagePanel) pageComponent)
                .filter(PdfPagePanel::isSelected)
                .sorted(Comparator.comparingInt(PdfPagePanel::getPageIndex))
                .collect(Collectors.toList());
    }

    private JMenuItem createActionMenuItem(String name, Consumer<ActionEvent> action) {
        final JMenuItem menuItem = new JMenuItem(name);
        menuItem.addActionListener(action::accept);
        return menuItem;
    }

    private interface PageRearrangement {
        default void rearrangePages(PDDocument pdDocument) {
            final List<PDPage> rearrangedPages = new ArrayList<>();

            for (int i = pdDocument.getNumberOfPages() - 1; i >= 0; i--) {
                rearrangedPages.add(rearrangePagesIterate(i));
                pdDocument.removePage(i);
            }

            for (int i = rearrangedPages.size() - 1; i >= 0; i--) {
                pdDocument.addPage(rearrangedPages.get(i));
            }
        }

        PDPage rearrangePagesIterate(int index);
    }

    private interface PageRearrangementRaw {
        default void rearrangePages(PDDocument pdDocument) {
            final List<PDPage> rearrangedPages = new ArrayList<>();

            rearrangePagesRaw(pdDocument, rearrangedPages);

            for (int i = pdDocument.getNumberOfPages() - 1; i >= 0; i--) {
                pdDocument.removePage(i);
            }

            for (PDPage rearrangedPage : rearrangedPages) {
                pdDocument.addPage(rearrangedPage);
            }
        }

        void rearrangePagesRaw(PDDocument pdDocument, List<PDPage> rearrangedPages);
    }

    private final static Map<PDPage, Image> cachedThumbnails = new HashMap<>();

    private final static ExecutorService executorService = Executors.newFixedThreadPool(3);

    private static class PdfPagePanel extends JLabel {

        private final int pageIndex;
        private final PDPage page;
        private boolean selected;

        public PdfPagePanel(PDFRenderer pdfRenderer, int pageIndex, PDPage page) {
            this.pageIndex = pageIndex;
            this.page = page;

            if (cachedThumbnails.containsKey(page)) {
                this.setIcon(new ImageIcon(cachedThumbnails.get(page)));
            } else {
                executorService.submit(() -> {
                    try {
                        final BufferedImage bim = pdfRenderer.renderImageWithDPI(pageIndex, PDF_PREVIEW_WIDTH / 2, ImageType.RGB);
                        // scale the image to fit the frame, but keep the aspect ratio
                        final int width = bim.getWidth();
                        final int height = bim.getHeight();

                        final int newWidth = PDF_PREVIEW_WIDTH;
                        final int newHeight = (int) (height * ((double) newWidth / width));

                        final Image scaledImage = bim.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH);
                        this.setIcon(new ImageIcon(scaledImage));

                        cachedThumbnails.put(page, scaledImage);

                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
            }

            setBorder(0);
        }

        @Override
        public void paint(Graphics g) {
            super.paint(g);
            // page number
            g.setColor(Color.BLACK);
            g.drawString(String.valueOf(pageIndex + 1), 5, 15);
        }

        public int getPageIndex() {
            return pageIndex;
        }

        public void toggleSelected() {
            setSelected(!selected);
        }

        public void setSelected(boolean selected) {
            this.selected = selected;
            setBorder(selected ? 1 : 0);
        }

        private void setBorder(int type) {
            switch (type) {
                case 1:
                    this.setBorder(BorderFactory.createDashedBorder(Color.RED, 3, 3, 3, true));
                    break;
                case 0:
                default:
                    this.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
                    break;
            }
        }

        public boolean isSelected() {
            return selected;
        }

        public PDPage getPage() {
            return page;
        }
    }
}
