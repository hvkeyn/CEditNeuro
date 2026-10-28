# Accessibility

CEditNeuro aims to be usable with Android accessibility services and system
display settings. This page states what is supported and what is still a
limitation.

## Supported

- TalkBack labels on the main navigation, chat controls, file actions, and
  settings controls.
- System font size and display size, within the limits of the Compose layout.
- The app's dark theme and the system light/dark preference.
- Touch targets on primary actions are sized for a finger, not a mouse.
- A visible focus state for keyboard and switch-access movement on standard
  controls.

## Known limitations

- The code editor is a text surface with its own scrolling and selection
  behaviour; very large files and some TalkBack gestures can be awkward.
- Diagrams and technical sheets are rendered in a WebView. Their text can be
  zoomed, but a screen reader may read the surrounding card rather than every
  label inside the drawing.
- The video reel timeline is a visual, drag-based editor. It is not fully
  operable with a screen reader.
- Some third-party pages opened in the reader keep their own accessibility
  behaviour.

## Report a barrier

Open an issue with the **accessibility** label and include:

- the Android version and device;
- the screen and the action;
- what TalkBack or the keyboard announced;
- whether font size or display size was changed.

Do not include personal notes, keys, or private screenshots. A screen recording
with private content removed is welcome.
