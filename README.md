# Simple PDF Rearrange

A Java Swing Utility to rearrange, delete, rotate pages or merge PDFs.

Click the PDFs to select them and right click to perform several actions. Depending on the amount of selected PDFs,
these actions are available:

- 0 Save
- 2 Swap
- 2 Insert second before first
- 2 Insert first after second
- 1+ Delete
- 1+ Rotate 90°
- \* Insert from file at end of document

Drag a PDF file into the frame to append it to the document. This is also the only way to load a new PDF.

## Build yourself using maven

```bash
git clone https://github.com/steos/jnafilechooser
cd jnafilechooser
git checkout f512011
mvn install
cd ..
git clone https://github.com/YanWittmann/simple-pdf-rearrange
cd simple-pdf-rearrange
mvn package
```
