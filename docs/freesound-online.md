# Freesound online supplement (Beta230)

Opt-in switch in the sound settings, off by default. API key is entered by the user and encrypted using Android Keystore. Blank key skips networking. No embedded credentials, no key in URLs or logs.

Book preparation order: enabled real sound pack → Freesound cache / online retrieval → enabled legacy generated bank → on-device generator. Mood music is not fetched from Freesound. Playback and book sync never initiate a Freesound API lookup. Turning the switch off skips this source; already copied book audio is managed by the normal recipe invalidation.

One search request per missing event kind or location description, up to 15 metadata candidates, one selected recording. Event matching requires object/action/material words in title or tags; a description anecdote is insufficient. Locations use the same conservative scene matcher as the real pack. No confident candidate means fallback. This is conservative metadata retrieval, not acoustic recognition or guaranteed semantic accuracy.

The phone uses HQ MP3 previews (original files require OAuth2), then platform decoding, event selection by positive spectral flux / energy (up to 8 seconds), normalization to −22 LUFS for events / −26 for location ambience, peak cap −3 dBFS and AAC encoding. Location recordings retain their complete length; this initial online path accepts 30–90 second sources. Cache keeps processed audio and a single JSON index with creator, title, source, license, original author notes and processing changes. Source preview is removed after processing; no per-sound license files. Delete-cache button removes this collection.

Requests have connection/read deadlines and bounded response sizes; 429 respects Retry-After. Search pacing <= about 55/min, failed network requests suppress further attempts for a minute, confident misses are cached for 24 hours. The next book reuses the cache offline while the source is enabled. This avoids a full database scan; no constant network-speed guarantee is possible. Low RAM, decoding errors, unsupported sources, licensing mismatch and cancellation safely skip the candidate.

Validation: 5 pure JVM tests for strict action/material matching, false-positive rejection, license URL whitelist, late-event selection and short stereo preservation; 23 existing sound-bank tests pass. Actual Android client compiled against the local API jar. Android runtime decoding/encoding has not been tested on the connected phone: the user launches tests manually. The normal platform AAC encoder is reused.

References: https://freesound.org/docs/api/overview.html , https://freesound.org/help/tos_api/ , https://creativecommons.org/licenses/by/4.0/legalcode.en#s3a . API use has terms separate from individual sound licenses. Do not mirror the entire Freesound database; this feature fetches specific assets needed for books.
