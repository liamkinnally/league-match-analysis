# Third-party notices

## shadcn/ui

`frontend/src/components/ui/chart.tsx` is adapted from [shadcn/ui Charts](https://ui.shadcn.com/docs/components/chart). The upstream [MIT license](https://github.com/shadcn-ui/ui/blob/main/LICENSE.md) applies to that component:

```text
MIT License

Copyright (c) 2023 shadcn

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Game assets and dependencies

League of Legends images and game data belong to Riot Games. See the [Riot notice](README.md#riot-notice).

The bounded rune layout datasets in `frontend/src/lib/game-assets/metadata/` are projections of Riot Data Dragon and CommunityDragon patch 16.17 and 16.18 metadata. Source URLs and separate original/projection SHA-256 hashes are recorded in `frontend/src/lib/game-assets/manifest.ts`. They describe rune identity and layout, not verified interpretations of recorded rune performance counters.

Provider artwork under `frontend/e2e/support/art/` is retained for deterministic, offline interface verification. Its source URLs, retrieval dates, and exact file hashes are recorded in that directory's `manifest.json`. The surrounding match fixtures are synthetic; the artwork is provider content. CommunityDragon serves extracted Riot assets and metadata; see its [asset documentation](https://github.com/CommunityDragon/docs/blob/master/assets.md).

The Apache Maven wrapper retains its Apache 2.0 notices in the wrapper files. Dependency licenses remain with their packages, including the SIL Open Font License 1.1 supplied with Instrument Sans. These notices do not grant a license to the rest of this repository.
