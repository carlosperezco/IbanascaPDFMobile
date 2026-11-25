# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the project follows [Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-11-25

Core: Ibanasca PDF Lab 1.1.2 (submodule `nucleo/`).

### Added
- First version of Ibanasca PDF Mobile, the Android version of Ibanasca PDF Lab, sharing
  the C++ core with the desktop version (git submodule `nucleo/`).
- Viewer with continuous scrolling, pinch zoom, double tap, fit width / fit page and
  go to page.
- File: new PDF, open (also "Open with" from other apps), Save As with the system file
  picker; the original file is never overwritten. Undo and redo.
- Share the current document (including unsaved changes) with other apps: mail,
  messaging, cloud storage. Only that copy is made readable to the chosen app.
- All core operations, with dialogs generated from the module descriptions: rotate,
  move, delete, duplicate and insert pages, add text and PNG images, merge, split or
  extract pages, visual signature and PAdES digital signature.
- Organize pages: reorder thumbnails by dragging them.
- Signature drawn with the finger (vector paths in the PDF).
- Digital signature with a `.p12`/`.pfx` file or with a certificate installed in
  Android (KeyChain), using only the system's Java classes.
- Interface in English, Spanish and Italian (the same language files as the desktop).
