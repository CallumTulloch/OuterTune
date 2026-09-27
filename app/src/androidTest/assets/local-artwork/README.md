# Synthetic local artwork fixture

`checkerboard.mp3` contains 0.15 seconds of generated silence and an original
1200 × 800 lossless PNG cover. Each pixel is opaque red when `(x + y) % 2 == 0`
and opaque blue otherwise. There is no recorded music or third-party artwork.

The one-pixel pattern lets `LocalArtworkResourceTest` detect accidental
downsampling as well as verify original dimensions through the native retriever
and actual Coil fetcher. The explicit-size test checks the existing 48 × 32
result when the requested frame is 48 × 48.

To regenerate with Python/Pillow and FFmpeg:

```python
from PIL import Image

red, blue = bytes([255, 0, 0]), bytes([0, 0, 255])
row, opposite = (red + blue) * 600, (blue + red) * 600
Image.frombytes("RGB", (1200, 800), (row + opposite) * 400).save("checkerboard.png")
```

```text
ffmpeg -f lavfi -i anullsrc=r=44100:cl=mono -i checkerboard.png -map 0:a -map 1:v -t 0.15 -c:a libmp3lame -b:a 32k -c:v copy -id3v2_version 3 -metadata "title=Synthetic artwork regression fixture" -metadata:s:v "title=Synthetic checkerboard" -metadata:s:v "comment=Cover (front)" checkerboard.mp3
```

Only the MP3 is needed by the tests; the intermediate PNG is not committed.
