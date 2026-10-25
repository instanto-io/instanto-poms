"""Exercise scheduling and failure propagation without a GitHub account or Maven cache."""
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import downstream


def graph(**dependencies):
    return {name: {"dependencies": deps} for name, deps in dependencies.items()}


class DownstreamTests(unittest.TestCase):
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
            with patch.dict('os.environ', {'MAVEN_TOKEN': 'do-not-persist-this-token'}):
                downstream.write_settings(path, [root])
            contents = path.read_text()
            self.assertIn('github-consumer', contents)
            self.assertIn('${env.MAVEN_TOKEN}', contents)
            self.assertNotIn('do-not-persist-this-token', contents)
            self.assertEqual(0o600, path.stat().st_mode & 0o777)

    def test_checked_in_catalog_reaches_widgets_port_from_scene_and_graphics(self):
        projects = downstream.load_catalog(Path(__file__).parents[1] / 'downstream-projects.json')
        for upstream in ('sarto-scene', 'sarto-graphics'):
            self.assertIn('ports', downstream.affected_projects(projects, upstream))


if __name__ == '__main__':
    unittest.main()
