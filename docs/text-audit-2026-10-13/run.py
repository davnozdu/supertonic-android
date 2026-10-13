"""Compile unchanged production Kotlin and existing tests; Android/JUnit adapters only.
No Android build, network, credentials, phone or speech. Requires cached Kotlin 2.0.21 and JDK.
Run: python3 docs/text-audit-2026-10-13/run.py
"""
import pathlib
import subprocess
import tempfile
import os

ROOT = pathlib.Path(__file__).resolve().parents[2]
CACHE = pathlib.Path.home() / '.gradle/caches/modules-2/files-2.1'
def jar(module, version):
    return str(next((CACHE / module / version).glob('*/*.jar')))
compiler = [jar('org.jetbrains.kotlin/kotlin-compiler-embeddable', '2.0.21'),
            jar('org.jetbrains.kotlin/kotlin-stdlib', '1.9.23'),
            jar('org.jetbrains.kotlin/kotlin-reflect', '1.9.23'),
            jar('org.jetbrains.intellij.deps/trove4j', '1.0.20200330'),
            jar('org.jetbrains/annotations', '13.0'),
            jar('org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm', '1.6.4')]
java = os.environ.get('AUDIT_JAVA', '/opt/homebrew/opt/openjdk@17/bin/java')
stdlib = compiler[1]
base = ROOT / 'app/src/main/java/com/brahmadeo/supertonic/tts'
testbase = ROOT / 'app/src/test/java/com/brahmadeo/supertonic/tts'
files = ['llm/StressCheck.kt', 'llm/NameStress.kt', 'llm/PreparedTextValidator.kt',
         'llm/PreparedTextRepair.kt', 'llm/NumeralForms.kt', 'utils/RussianNames.kt',
         'utils/YoRestore.kt', 'utils/RussianYoPolicy.kt', 'utils/BookTextSpacing.kt', 'kokoro/KokoroG2p.kt',
         'utils/RussianNumberNormalizer.kt', 'utils/RussianBookNormalizer.kt', 'utils/RussianDates.kt']
tests = ['llm/StressCheckTest.kt', 'llm/NameStressTest.kt', 'llm/PreparedTextValidatorTest.kt',
         'llm/PreparedTextRepairTest.kt', 'utils/NamesAndYoTest.kt', 'utils/RussianYoPolicyTest.kt',
         'utils/BookTextSpacingTest.kt']
with tempfile.TemporaryDirectory(prefix='mytts-text-audit-') as tmp:
    tmp = pathlib.Path(tmp)
    (tmp / 'Context.kt').write_text('''package android.content
open class Context { val assets = Assets() }
class Assets { fun open(name: String): java.io.InputStream = error("Android assets unavailable in audit: $name") }
''')
    (tmp / 'Junit.kt').write_text('''package org.junit
@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.RUNTIME) annotation class Test
''')
    (tmp / 'Assert.java').write_text('''package org.junit;
public class Assert {
 public static void assertEquals(Object a, Object b) { if (!java.util.Objects.equals(a,b)) throw new AssertionError("expected="+a+" actual="+b); }
 public static void assertTrue(boolean v) { if (!v) throw new AssertionError(); }
 public static void assertFalse(boolean v) { assertTrue(!v); }
 public static void assertNull(Object v) { assertEquals(null,v); }
 public static void assertNotNull(Object v) { assertTrue(v!=null); }
}
''')
    out = tmp / 'classes'
    out.mkdir()
    subprocess.run([str(pathlib.Path(java).with_name('javac')), '-d', str(out), str(tmp/'Assert.java')], check=True)
    subprocess.run([java, '-cp', ':'.join(compiler), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', f'{stdlib}:{out}', '-d', str(out),
                    str(tmp/'Context.kt'), str(tmp/'Junit.kt'), str(pathlib.Path(__file__).with_name('Audit.kt')),
                    *[str(base/f) for f in files], *[str(testbase/f) for f in tests]], check=True)
    subprocess.run([java, '-cp', f'{out}:{stdlib}', 'com.brahmadeo.supertonic.tts.llm.AuditKt',
                    str(ROOT/'app/src/main/assets/names_ru.tsv')], check=True)
