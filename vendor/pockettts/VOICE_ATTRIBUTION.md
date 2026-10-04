# Bundled official Pocket TTS voices

The release model packs bundle only the official default reference voices
defined by Kyutai Pocket TTS. They do not contain user-imported recordings.

| Pack | Voice | Upstream recording | License and attribution |
| --- | --- | --- | --- |
| English | Alba | [`alba-mackenna/casual.wav`](https://huggingface.co/kyutai/tts-voices/blob/main/alba-mackenna/casual.wav) | Voice performance by Alba MacKenna; CC BY 4.0 |
| German | Jürgen | [`de-DE-juergen.mp3`](https://huggingface.co/kyutai/pocket-tts/blob/64ab7d24c479d736a83b8cc666c4a776fca30fda/de-DE-juergen.mp3) | Kyutai Pocket TTS model repository; CC BY 4.0 |
| Italian | Giovanni | [`common_voice_it_36520747-enhanced-v2.mp3`](https://huggingface.co/kyutai/pocket-tts/blob/64ab7d24c479d736a83b8cc666c4a776fca30fda/common_voice_it_36520747-enhanced-v2.mp3) | Kyutai Pocket TTS model repository; CC BY 4.0 |
| Portuguese | Rafael | [`g-Vi8PgmSY0-enhanced-v2.wav`](https://huggingface.co/kyutai/pocket-tts/blob/64ab7d24c479d736a83b8cc666c4a776fca30fda/g-Vi8PgmSY0-enhanced-v2.wav) | Kyutai Pocket TTS model repository; CC BY 4.0 |
| Spanish | Lola | [`common_voice_es_19762977-enhanced-v2.mp3`](https://huggingface.co/kyutai/pocket-tts/blob/64ab7d24c479d736a83b8cc666c4a776fca30fda/common_voice_es_19762977-enhanced-v2.mp3) | Kyutai Pocket TTS model repository; CC BY 4.0 |

License: https://creativecommons.org/licenses/by/4.0/

Changes made for the Android packs: each upstream recording is converted to
24 kHz mono 16-bit PCM WAV and renamed to its stable Pocket TTS voice ID. No
voice is synthesized, edited to impersonate another speaker, or mixed with a
user recording during packaging.

The names above are Pocket TTS catalog identifiers. Their inclusion does not
imply endorsement by Kyutai or by any recorded speaker. Other rights, including
privacy, publicity, and moral rights, may still apply. Use the voices lawfully
and do not present generated speech as an authentic recording of a real person.
