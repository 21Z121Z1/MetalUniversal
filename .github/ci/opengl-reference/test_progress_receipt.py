"""Source wiring checks plus isolated execution of the actual Receipt implementation.

The JDK source launcher tests only the extracted watchdog/atomic writer, with a tiny
JSON test double and a shorter deadline. It does not compile Minecraft/Fabric.
"""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest

DRIVER = Path(__file__).parent / 'src/main/java/com/metallum/reference/OpenGlReferenceGameTest.java'


class ProgressReceiptTest(unittest.TestCase):
    def test_phase_receipts_precede_waits_and_pass_follows_cleanup(self):
        source = DRIVER.read_text()
        for phase, operation in [
            ('setup', 'Path harness'), ('world-create', 'try (var world'),
            ('initial-40-ticks', 'context.waitTicks(40)'),
            ('full-chunks', 'context.waitFor('),
            ('render-ready', 'world.getConnection().waitForChunksRender'),
            ('iris-reload', 'Iris.reload()'),
            ('capture', 'context.computeOnClient(this::capture)'),
            ('cleanup-server-halt', 'server.halt(false)'),
        ]:
            self.assertLess(source.index('receipt.checkpoint(report, "' + phase + '")'), source.index(operation))
        self.assertLess(source.index('report.addProperty("expectedChunks"'), source.index('context.waitFor('))
        self.assertLess(source.index('report.addProperty("receivedChunks", received)'), source.index('return received == chunks.size()'))
        self.assertLess(source.index('receipt.checkpoint(report, "failure-before-cleanup")'), source.index('} finally {'))
        self.assertLess(source.index('receipt.checkpoint(report, "cleanup-world-close")'), source.index('receipt.finish(report, "passed")'))
        self.assertIn('}, 6000);', source)
        self.assertIn('waitForChunksRender(false, 1200)', source)

    def run_receipt(self, success=False, failed_cleanup=False, mode=None):
        if not shutil.which('java'):
            self.skipTest('Java source launcher unavailable')
        source = DRIVER.read_text()
        start = source.index('    private static final class Receipt')
        end = source.index('    private static void require(', start)
        implementation = source[start:end].replace('Duration.ofMinutes(15)', 'Duration.ofMillis(500)')
        implementation = implementation.replace('Duration.ofSeconds(5)', 'Duration.ofMillis(500)')
        # Inject faults into the extracted writer; production has no test switch.
        implementation = implementation.replace('            Files.createDirectories(OUTPUT);',
            '            probeBeforeWrite(report, name);\n            Files.createDirectories(OUTPUT);')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            harness = root / 'ReceiptProbe.java'
            harness.write_text('''import java.nio.file.*;
import java.time.*;
import java.util.*;
class ReceiptProbe {
    static Path OUTPUT;
    static String mode;
    static void probeBeforeWrite(JsonObject report, String name) {
        if ((mode.equals("blocked-writer") && name.equals("reference.json") &&
                report.get("phase").getAsString().equals("cleanup-world-close")) ||
                (mode.equals("blocked-diagnostic") && name.equals("timeout.json"))) {
            new java.util.concurrent.Semaphore(0).acquireUninterruptibly();
        }
        if (mode.equals("finish-race") && name.equals("reference.json") &&
                report.get("status").getAsString().equals("passed")) {
            try { Thread.sleep(700); } catch (InterruptedException failure) { throw new RuntimeException(failure); }
        }
    }
    static void require(boolean ok, String reason) { if (!ok) throw new IllegalStateException(reason); }
    public static void main(String[] args) throws Exception {
        OUTPUT = Path.of(args[0]);
        mode = args[1];
        JsonObject report = new JsonObject();
        report.addProperty("status", "running");
        report.addProperty("expectedChunks", 637);
        report.addProperty("receivedChunks", 123);
        Receipt receipt = new Receipt(report);
        if (args[1].equals("failed-cleanup")) {
            report.addProperty("status", "correctness-failed");
            report.addProperty("failure", "original capture failure");
            report.addProperty("failurePhase", "capture");
            receipt.checkpoint(report, "failure-before-cleanup");
        }
        receipt.checkpoint(report, "cleanup-world-close");
        if (args[1].equals("success") || args[1].equals("finish-race")) {
            try { receipt.finish(report, "passed"); }
            catch (IllegalStateException expired) {
                if (!mode.equals("finish-race")) throw expired;
                new java.util.concurrent.Semaphore(0).acquireUninterruptibly();
            }
            Thread.sleep(1200);
        } else {
            report.addProperty("receivedChunks", 999); // Must not mutate the saved snapshot.
            new java.util.concurrent.Semaphore(0).acquire();
        }
    }
''' + implementation + '''
    // Minimal primitive-only JSON test double: production uses Gson.
    static class JsonObject {
        final Map<String, Object> values = new LinkedHashMap<>();
        void addProperty(String key, Object value) { values.put(key, value); }
        JsonObject deepCopy() { JsonObject copy = new JsonObject(); copy.values.putAll(values); return copy; }
        Value get(String key) { return new Value(values.get(key)); }
    }
    record Value(Object value) { String getAsString() { return value.toString(); } }
    static class GsonBuilder {
        GsonBuilder setPrettyPrinting() { return this; }
        GsonBuilder create() { return this; }
        String toJson(JsonObject object) {
            StringJoiner json = new StringJoiner(",", "{", "}");
            object.values.forEach((key, value) -> json.add("\\\"" + key + "\\\":" +
                (value instanceof Number ? value.toString() : "\\\"" + value + "\\\"")));
            return json.toString();
        }
    }
}
''')
            started = time.monotonic()
            result = subprocess.run(['java', str(harness), str(root), mode or ('success' if success else 'failed-cleanup' if failed_cleanup else 'timeout')],
                                    capture_output=True, text=True, timeout=8)
            self.assertLess(time.monotonic() - started, 8)
            self.assertEqual(result.returncode, 0 if success else 124, result.stdout + result.stderr)
            if mode == 'blocked-diagnostic':
                self.assertFalse((root / 'timeout.json').exists())
                self.assertEqual(json.loads((root / 'reference.json').read_text())['status'], 'running')
                return
            receipt_path = root / ('timeout.json' if mode == 'blocked-writer' else 'reference.json')
            report = json.loads(receipt_path.read_text())
            self.assertFalse((root / 'reference.json.pending').exists())
            if success:
                self.assertEqual(report['status'], 'passed')
                self.assertEqual(report['phase'], 'complete')
                self.assertFalse((root / 'timeout-threads.txt').exists())
            else:
                self.assertEqual(report['status'], 'wall-clock-timeout')
                self.assertEqual(report['phase'], 'complete' if mode == 'finish-race' else 'cleanup-world-close')
                self.assertEqual(report['expectedChunks'], 637)
                self.assertEqual(report['receivedChunks'], 123)
                self.assertEqual(json.loads((root / 'timeout.json').read_text())['status'], 'wall-clock-timeout')
                self.assertGreaterEqual(report['elapsedMillis'], 500)
                if failed_cleanup:
                    self.assertEqual(report['failure'], 'original capture failure')
                    self.assertEqual(report['failurePhase'], 'capture')
                self.assertIn('opengl-reference-timeout-hard-stop', (root / 'timeout-threads.txt').read_text())
                self.assertIn('ReceiptProbe', (root / 'timeout-threads.txt').read_text())

    def test_wall_clock_terminates_blocked_cleanup_and_keeps_snapshot(self):
        self.run_receipt()

    def test_original_failure_survives_blocked_cleanup(self):
        self.run_receipt(failed_cleanup=True)

    def test_blocked_checkpoint_writer_cannot_block_hard_stop(self):
        self.run_receipt(mode='blocked-writer')

    def test_blocked_timeout_diagnostic_cannot_block_hard_stop(self):
        self.run_receipt(mode='blocked-diagnostic')

    def test_timeout_wins_race_with_blocked_finish_write(self):
        self.run_receipt(mode='finish-race')

    def test_completion_cancels_watchdog(self):
        self.run_receipt(success=True)


if __name__ == '__main__':
    unittest.main()
