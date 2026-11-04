# Rich document Word export

This contribution adds an offline DOCX writer on top of the versioned rich-document
storage in PR #179. It does not add an editor screen or change the existing
Markdown notes. The UI contribution will connect the writer to Android's document
creation picker so users choose the destination.

## Preserved content

The writer retains paragraph boundaries, empty paragraphs, line breaks, tabs,
Unicode text and both U+00A0 and U+202F nonbreaking spaces. Text is escaped for
XML; unsupported XML control characters and unpaired surrogates become U+FFFD.
Carriage returns are ignored, while line feeds produce Word line breaks.

Runs retain bold, italic, underline, optional RGB colour and font size.
Paragraphs retain left, centre, right or justified alignment, indentation,
three heading levels, bullets and numbered lists. Adjacent paragraphs of the same
list kind share a numbering instance; a separate list restarts numbering.

Page colour and an optional supplied JPEG can be included. The document is
validated before the output is written; a required but missing image or one
larger than 2 MiB is rejected. Images are embedded, never linked remotely.
Photo crop, intensity and positioning metadata are not reproduced in Word:
the JPEG is used as a page background. Background display/printing depends on
the receiving Word processor's settings.

## Package and stream contract

The output is a ZIP-based Office Open XML document. Content types and internal
relationships refer only to generated parts. There are no macros, remote
relationships, analytics or network requests.

The writer closes its output stream after writing the package. Validation errors
leave the destination untouched. A later I/O failure can leave partial output;
the caller must report the failure and handle the destination accordingly.

## Validation

DocumentWordExportTest parses all generated XML, verifies package targets and
checks text, styles, numbering, backgrounds and rejected inputs. A synthetic
27,000-word note exercises the format's upper text range. A small fictional
review document is generated under app/build/reports/document-word-export for
opening in an independent Word processor.

The storage and existing Markdown note tests run alongside the export tests.
Device recordings belong to the later UI contribution, where export becomes
available to users.
