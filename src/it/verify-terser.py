#!/usr/bin/env python3
"""Compile and package real TeaVM assets through the inherited Terser execution."""
from pathlib import Path
import json
import os
import subprocess
import zipfile
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / 'target/terser-inheritance'
OUTPUT.mkdir(parents=True, exist_ok=True)
SOURCE = '''package example;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSExport;
public class Reproducer {
    public static void main(String[] args) { }
    @JSExport public static int compute(int value) { return addSeven(value); }
    @JSExport public static int global(int value) { return readGlobal(value); }
    @JSBody(params = "value", script = "var b = 7; return value + b;")
    private static native int addSeven(int value);
    @JSBody(params = "value", script = "var offset = b; return value + offset;")
    private static native int readGlobal(int value);
}
'''


def fixture(name, module_type, filename):
    folder = OUTPUT / name
    source = folder / 'src/main/java/example/Reproducer.java'
    source.parent.mkdir(parents=True, exist_ok=True)
    source.write_text(SOURCE)
    parent = escape(os.path.relpath(ROOT / 'instanto-teavm-pom/pom.xml', folder))
    (folder / 'pom.xml').write_text('''<project xmlns="http://maven.apache.org/POM/4.0.0">
      <modelVersion>4.0.0</modelVersion>
      <parent><groupId>io.instanto</groupId><artifactId>instanto-teavm-pom</artifactId>
        <version>0.1.0-SNAPSHOT</version><relativePath>''' + parent + '''</relativePath></parent>
      <artifactId>terser-''' + name + '''</artifactId>
      <properties>
        <instanto.terser.phase>prepare-package</instanto.terser.phase>
        <instanto.terser.directory>${project.build.outputDirectory}/META-INF/resources/js</instanto.terser.directory>
        <instanto.terser.file>''' + filename + '''</instanto.terser.file>
      </properties>
      <dependencies>
        <dependency><groupId>org.teavm</groupId><artifactId>teavm-classlib</artifactId></dependency>
        <dependency><groupId>org.teavm</groupId><artifactId>teavm-jso</artifactId></dependency>
      </dependencies>
      <build><plugins>
        <plugin><groupId>org.teavm</groupId><artifactId>teavm-maven-plugin</artifactId>
          <executions><execution><id>compile-js</id><phase>prepare-package</phase>
            <goals><goal>compile</goal></goals></execution></executions>
          <configuration><mainClass>example.Reproducer</mainClass><targetType>JAVASCRIPT</targetType>
            <targetDirectory>${instanto.terser.directory}</targetDirectory>
            <targetFileName>${instanto.terser.file}</targetFileName>
            <jsModuleType>''' + module_type + '''</jsModuleType><optimizationLevel>ADVANCED</optimizationLevel>
          </configuration></plugin>
        <plugin><groupId>org.codehaus.mojo</groupId><artifactId>exec-maven-plugin</artifactId>
          <!-- An unrelated direct exec command must not leak into Terser. -->
          <configuration><executable>should-not-run</executable><commandlineArgs>unused</commandlineArgs></configuration>
        </plugin>
      </plugins></build>
    </project>''')
    return folder


def build(folder, label, *options):
    log = OUTPUT / (label + '.log')
    command = ['mvn', '-B', '-ntp', '-f', str(folder / 'pom.xml'),
               '-Dinstanto.teavm.stockCompiler', '-Dspotless.skip=true', '-Dspotbugs.skip=true',
               '-Dmaven.javadoc.skip=true', 'clean', 'package', *options]
    with log.open('w') as output:
        result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError(f'{label} failed: {log}')
    return log.read_text()


def check(folder, filename, esm):
    js = folder / 'target/classes/META-INF/resources/js' / filename
    code = js.read_text()
    source_map = json.loads(js.with_suffix(js.suffix + '.map').read_text())
    assert source_map['mappings']
    java_source = next(s for s in source_map['sources'] if s.endswith('example/Reproducer.java'))
    copied_source = js.parent / source_map.get('sourceRoot', '') / java_source
    assert copied_source.is_file(), f'Source map points to missing Java source: {copied_source}'
    assert copied_source.read_text() == SOURCE
    assert 'sourceMappingURL=' + filename.replace(' ', '%20') + '.map' in code or 'sourceMappingURL=' + filename + '.map' in code
    # Exercise a public export and a free global after minification.
    script = '''const fs = require('fs'), vm = require('vm');
      const file = process.argv[1];
      (async () => {
        let api;
        if (process.argv[2] === 'esm') {
          globalThis.b = 7;
          api = await import(require('url').pathToFileURL(file));
        } else {
          const context = vm.createContext({exports: {}, console});
          vm.runInContext('let b = 7;', context);
          vm.runInContext(fs.readFileSync(file, 'utf8'), context);
          api = context.exports;
        }
        if (typeof api.main !== 'function' || api.compute(42) !== 49 || api.global(42) !== 49)
          throw new Error('Export or JSBody behaviour changed');
      })().catch(e => { console.error(e); process.exitCode = 1; });
    '''
    subprocess.run(['node', '-e', script, str(js), 'esm' if esm else 'classic'], check=True)
    jar = folder / ('target/terser-' + folder.name.replace(' ', '-') + '-0.1.0-SNAPSHOT.jar')
    with zipfile.ZipFile(jar) as archive:
        assert archive.read('META-INF/resources/js/' + filename) == js.read_bytes()
        assert archive.read('META-INF/resources/js/' + filename + '.map') == js.with_suffix(js.suffix + '.map').read_bytes()
    return len(code.encode())


results = []
for name, module_type, filename in [('classic', 'UMD', 'bundle.js'), ('module space', 'ES2015', 'bundle space.mjs')]:
    folder = fixture(name, module_type, filename)
    # Maven artifact IDs cannot contain spaces, but paths and asset names can.
    p = folder / 'pom.xml'
    p.write_text(p.read_text().replace('<artifactId>terser-module space</artifactId>', '<artifactId>terser-module-space</artifactId>'))
    log = build(folder, name + '-minified')
    assert log.index('(compile-js)') < log.index('(minify-teavm-js)')
    size = check(folder, filename, module_type == 'ES2015')
    log = build(folder, name + '-skipped', '-Dinstanto.terser.skip=true')
    original = check(folder, filename, module_type == 'ES2015')
    assert size < original, (size, original)
    results.append({'fixture': name, 'original_bytes': original, 'minified_bytes': size})
    print('PASS', results[-1], flush=True)
(OUTPUT / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
