"""Exercise scheduling and failure propagation without a GitHub account or Maven cache."""
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch, MagicMock

import downstream


def graph(**dependencies):
    return {name: {"dependencies": deps} for name, deps in dependencies.items()}


class DownstreamTests(unittest.TestCase):
    def test_upstream_copy_preserves_commit_and_leaves_enclosing_checkout_untouched(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / 'outer' / 'upstream'
            source.mkdir(parents=True)
            (source.parent / 'pom.xml').write_text('<project/>')
            def git(*args):
                return subprocess.check_output(['git', *args], cwd=source, text=True).strip()
            git('init', '-q')
            (source / 'pom.xml').write_text('committed pom')
            git('add', 'pom.xml')
            git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                'commit', '-qm', 'Pinned source')
            revision = git('rev-parse', 'HEAD')
            (source / 'pom.xml').write_text('uncommitted work')
            destination = root / 'isolated' / 'upstream'
            destination.parent.mkdir()
            self.assertEqual(revision, downstream.isolate_upstream(source, destination))
            self.assertEqual('committed pom', (destination / 'pom.xml').read_text())
            self.assertEqual('uncommitted work', (source / 'pom.xml').read_text())
            self.assertEqual(revision, subprocess.check_output(
                ['git', 'rev-parse', 'HEAD'], cwd=destination, text=True).strip())
            self.assertFalse((destination.parent / 'pom.xml').exists())

    def test_nested_checkout_pins_workspace_root_without_skipping_tests(self):
        with tempfile.TemporaryDirectory() as tmp:
            outer = Path(tmp)
            source = outer / 'upstream checkout'
            source.mkdir()
            (outer / 'pom.xml').write_text('<project/>')
            process = MagicMock()
            process.stdout = iter(['verification completed\n'])
            process.wait.return_value = 0
            with patch.object(downstream.subprocess, 'Popen', return_value=process) as launch:
                self.assertTrue(downstream.run_maven(
                    source, ['clean', 'install'], outer / 'm2', outer / 'settings.xml', outer / 'run.log'))
            args, kwargs = launch.call_args
            self.assertIn('-Dmaven.top-level-basedir=' + str(source.resolve()), args[0])
            self.assertIn('-Daether.transport.http.retryHandler.serviceUnavailable=429,502,503,504', args[0])
            self.assertIn('-Dmaven.artifact.threads=2', args[0])
            self.assertEqual(source, kwargs['cwd'])
            self.assertEqual(['clean', 'install'], args[0][-2:])
            self.assertNotIn('-DskipTests', args[0])

    def test_run_maven_allows_overriding_resilience_flags(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp)
            process = MagicMock()
            process.stdout = iter([])
            process.wait.return_value = 0
            with patch.object(downstream.subprocess, 'Popen', return_value=process) as launch:
                self.assertTrue(downstream.run_maven(
                    source, ['-Dmaven.artifact.threads=4', 'verify'], source / 'm2', source / 's.xml', source / 'run.log'))
            cmd = launch.call_args[0][0]
            self.assertNotIn('-Dmaven.artifact.threads=2', cmd)
            self.assertIn('-Dmaven.artifact.threads=4', cmd)
            self.assertIn('-Daether.transport.http.retryHandler.count=8', cmd)

    def test_transitive_diamond_runs_once_and_before_consumers(self):
        projects = graph(root=[], left=['root'], right=['root'], leaf=['left', 'right'], unrelated=[])
        affected = downstream.affected_projects(projects, 'root')
        self.assertEqual({'root', 'left', 'right', 'leaf'}, affected)
        calls = []
        groups = downstream.build_groups(projects, affected)
        result = downstream.execute_groups(projects, groups, 'root',
                                           lambda name, phase: calls.append((name, phase)) or True, True)
        self.assertEqual([('left', 'verify'), ('right', 'verify'), ('leaf', 'verify')], calls)
        self.assertEqual({'passed'}, set(result.values()))

    def test_failure_blocks_dependants_but_not_independent_branch(self):
        projects = graph(root=[], bad=['root'], good=['root'], leaf=['bad'])
        calls = []
        def build(name, phase):
            calls.append(name)
            return name != 'bad'
        result = downstream.execute_groups(projects, downstream.build_groups(projects, set(projects)),
                                           'root', build, True)
        self.assertEqual(['bad', 'good'], calls)
        self.assertEqual({'root': 'passed', 'bad': 'failed', 'good': 'passed', 'leaf': 'blocked'}, result)

    def test_cycle_is_installed_once_and_all_members_reverified(self):
        projects = graph(root=['second'], second=['root'], leaf=['second'])
        calls = []
        result = downstream.execute_groups(projects, downstream.build_groups(projects, set(projects)),
                                           'root', lambda name, phase: calls.append((name, phase)) or True, True)
        self.assertEqual([('second', 'install-cycle'), ('root', 'verify-cycle'),
                          ('second', 'verify-cycle'), ('leaf', 'verify')], calls)
        self.assertEqual({'passed'}, set(result.values()))

    def test_failed_cycle_does_not_release_partial_artifacts_to_consumers(self):
        projects = graph(root=['second'], second=['root'], leaf=['root'])
        calls = []
        def build(name, phase):
            calls.append((name, phase))
            return phase != 'verify-cycle' or name != 'second'
        result = downstream.execute_groups(projects, downstream.build_groups(projects, set(projects)),
                                           'root', build, True)
        self.assertEqual('failed', result['second'])
        self.assertEqual('blocked', result['root'])
        self.assertEqual('blocked', result['leaf'])
        self.assertNotIn(('leaf', 'verify'), calls)

    def test_failed_upstream_blocks_entire_cascade(self):
        projects = graph(root=[], leaf=['root'])
        result = downstream.execute_groups(projects, downstream.build_groups(projects, set(projects)),
                                           'root', lambda *_: self.fail('Must not build downstream'), False)
        self.assertEqual({'root': 'failed', 'leaf': 'blocked'}, result)

    def test_unknown_upstream_has_no_consumers(self):
        self.assertEqual(set(), downstream.affected_projects(graph(root=[]), 'other'))

    def test_charba_publication_reaches_scene_and_ports_after_compatibility_tools(self):
        projects = downstream.load_catalog(Path(__file__).parents[1] / 'downstream-projects.json')
        affected = downstream.affected_projects(projects, 'teavm-compat')
        self.assertTrue({'teavm-charba', 'tealeaf', 'sarto-scene', 'ports'} <= affected)
        groups = downstream.build_groups(projects, affected)
        positions = {name: index for index, group in enumerate(groups) for name in group}
        self.assertLess(positions['teavm-compat'], positions['teavm-charba'])
        self.assertLess(positions['teavm-compat'], positions['tealeaf'])
        self.assertLess(positions['teavm-charba'], positions['sarto-scene'])
        self.assertLess(positions['sarto-scene'], positions['ports'])

    def test_catalog_rejects_unknown_dependency_and_untrusted_repository(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'catalog.json'
            for projects in [graph(root=['missing']),
                             {'root': {'dependencies': [], 'repository': 'other/root'}}]:
                path.write_text(json.dumps({'schema_version': 1, 'organisation': 'instanto-io', 'projects': projects}))
                with self.assertRaises(ValueError):
                    downstream.load_catalog(path)

    def test_settings_reference_credentials_and_discover_consumer_server_ids(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'pom.xml').write_text('<project><id>github-consumer</id></project>')
            path = root / 'settings.xml'
            with patch.dict('os.environ', {'MAVEN_TOKEN': 'do-not-persist-this-token', 'MAVEN_CENTRAL_MIRROR_URL': ''}):
                downstream.write_settings(path, [root])
            contents = path.read_text()
            self.assertIn('github-consumer', contents)
            self.assertIn('${env.MAVEN_TOKEN}', contents)
            self.assertNotIn('do-not-persist-this-token', contents)
            self.assertNotIn('<mirrors>', contents)
            self.assertEqual(0o600, path.stat().st_mode & 0o777)

    def test_settings_include_central_mirror_when_env_set_and_strip_preserves_cache(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            path = root / 'settings.xml'
            m2 = root / 'm2'
            cache_dir = root / 'runner-cache'
            rel = m2 / 'org' / 'example' / 'dep' / '1.0'
            rel.mkdir(parents=True)
            (rel / '_remote.repositories').write_text('dep-1.0.jar>central=\n')
            (rel / 'dep-1.0.jar').write_text('jar')
            with patch.dict('os.environ', {
                'MAVEN_CENTRAL_MIRROR_URL': 'https://maven-central.storage-download.googleapis.com/maven2',
                'MAVEN_DOWNLOAD_CACHE': str(cache_dir),
            }):
                downstream.write_settings(path, [root])
                central_path = root / 'central-settings.xml'
                downstream.write_central_settings(central_path)
                downstream.strip_remote_repositories(m2)
            contents = path.read_text()
            self.assertIn('<id>central</id>', contents)
            self.assertIn('<mirrorOf>central</mirrorOf>', contents)
            self.assertIn('${env.MAVEN_CENTRAL_MIRROR_URL}', contents)
            central_contents = central_path.read_text()
            self.assertIn('<id>central</id>', central_contents)
            self.assertIn('<mirrorOf>central</mirrorOf>', central_contents)
            self.assertNotIn('forgejo-instanto', central_contents)
            self.assertNotIn('<servers>', central_contents)
            empty_central = root / 'empty-central-settings.xml'
            with patch.dict('os.environ', {'MAVEN_CENTRAL_MIRROR_URL': ''}):
                downstream.write_central_settings(empty_central)
            self.assertNotIn('<mirrors>', empty_central.read_text())
            self.assertFalse((rel / '_remote.repositories').exists())
            self.assertTrue((cache_dir / 'central/org/example/dep/1.0/dep-1.0.jar').is_file())

    def test_checked_in_catalog_reaches_widgets_port_from_scene_and_graphics(self):
        projects = downstream.load_catalog(Path(__file__).parents[1] / 'downstream-projects.json')
        for upstream in ('sarto-scene', 'sarto-graphics'):
            self.assertIn('ports', downstream.affected_projects(projects, upstream))

    def test_checked_in_catalog_invariants_and_sccs(self):
        projects = downstream.load_catalog(Path(__file__).parents[1] / 'downstream-projects.json')
        self.assertGreaterEqual(len(projects), 44)
        self.assertNotIn('domino-widgets', projects['sarto']['dependencies'])
        self.assertNotIn('sarto', projects['sarto-poms']['dependencies'])
        self.assertNotIn('ports', projects['ports'].get('runner_labels', []))
        self.assertIn('bootstrap-widgets', projects['ports']['dependencies'])
        self.assertIn('teavm-compat', projects['ports']['dependencies'])
        groups = downstream.build_groups(projects, set(projects))
        pos = {name: i for i, g in enumerate(groups) for name in g}
        self.assertLess(pos['instanto-poms'], pos['instanto-teavm'])
        self.assertLess(pos['instanto-teavm'], pos['teavm-compat'])
        self.assertLess(pos['teavm-compat'], pos['bootstrap-widgets'])
        self.assertLess(pos['bootstrap-widgets'], pos['ports'])

    def test_expand_placeholders_and_env_preserve_unrelated_braces(self):
        ph = {
            'checkout': '/work/repo',
            'settings': '/work/settings.xml',
            'm2': '/work/m2',
            'mvn': '/work/repo/mvnw',
            'runner_temp': '/tmp/rt',
        }
        args = downstream.expand_placeholders(
            ['-Dsonar.coverageReportPaths={checkout}/target/teavm-coverage.xml', '-DargLine=@{instanto.coverage.argLine}'],
            ph,
        )
        self.assertEqual(
            ['-Dsonar.coverageReportPaths=/work/repo/target/teavm-coverage.xml', '-DargLine=@{instanto.coverage.argLine}'],
            args,
        )
        env = downstream.expand_env({'GRADLE_USER_HOME': '{runner_temp}/gradle'}, ph)
        self.assertEqual({'GRADLE_USER_HOME': '/tmp/rt/gradle'}, env)

    def test_catalog_validates_env_and_post_verify_commands(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'catalog.json'
            bad_cases = [
                {'root': {'dependencies': [], 'env': {'X': 123}}},
                {'root': {'dependencies': [], 'post_verify_commands': [{'cmd': []}]}},
                {'root': {'dependencies': [], 'post_verify_commands': [{'cmd': ['echo'], 'cwd': ''}]}},
                {'root': {'dependencies': [], 'post_verify_commands': [{'cmd': ['echo'], 'if_sonar': 'yes'}]}},
            ]
            for projects in bad_cases:
                path.write_text(json.dumps({'schema_version': 1, 'organisation': 'instanto-io', 'projects': projects}))
                with self.assertRaises(ValueError):
                    downstream.load_catalog(path)


if __name__ == '__main__':
    unittest.main()
