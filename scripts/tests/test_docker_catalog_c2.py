"""Pure regression tests: no database, Docker mutation, credentials or role creation."""
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import docker_catalog_c2 as c2


def inventory():
    names = ['ls_core_dev', 'ls_core_docker_dev', 'postgres', 'template0', 'template1']
    rows = []
    for database in names:
        for role in (c2.READER, c2.RUNTIME):
            rows.append(dict(database=database, role=role, owner='ls_core_docker',
                allowconn=database != 'template0',
                connect=(role == c2.RUNTIME and database == 'ls_core_dev') or database == 'template0',
                create=False, temporary=database in c2.EXCEPTION_DATABASES,
                public_temp=database in c2.EXCEPTION_DATABASES, direct_temp=False, grantable=False))
    return dict(database_names=names, custom_roles=sorted([c2.READER, c2.RUNTIME, 'ls_core_docker', 'ls_core_migrator_dev']),
                memberships=0, sessions=0, schema_create=0, bad_roles=0, privileges=rows)


class DockerCatalogC2Test(unittest.TestCase):
    def test_exact_docker_baseline_passes(self):
        c2.validate_inventory(inventory())

    def test_connect_and_create_are_never_relaxed(self):
        for role in (c2.READER, c2.RUNTIME):
            for field in ('connect', 'create'):
                with self.subTest(role=role, field=field):
                    state = inventory()
                    next(row for row in state['privileges'] if row['database'] == 'postgres' and row['role'] == role)[field] = True
                    with self.assertRaises(c2.Stopped):
                        c2.validate_inventory(state)

    def test_only_public_non_grantable_temp_is_allowed(self):
        for field, value in [('public_temp', False), ('direct_temp', True), ('grantable', True)]:
            state = inventory()
            next(row for row in state['privileges'] if row['database'] == 'postgres')[field] = value
            with self.subTest(field=field), self.assertRaises(c2.Stopped):
                c2.validate_inventory(state)

    def test_temp_on_target_or_template1_is_rejected(self):
        for database in ('ls_core_dev', 'template1'):
            state = inventory()
            row = next(row for row in state['privileges'] if row['database'] == database)
            row.update(temporary=True, public_temp=True)
            with self.subTest(database=database), self.assertRaises(c2.Stopped):
                c2.validate_inventory(state)

    def test_unknown_databases_roles_or_existing_c2_are_rejected(self):
        for key, value in [('database_names', 'another_db'), ('custom_roles', 'unexpected_role'),
                           ('custom_roles', 'ls_core_catalog_service_dev')]:
            state = inventory()
            state[key].append(value)
            with self.subTest(value=value), self.assertRaises(c2.Stopped):
                c2.validate_inventory(state)

    def test_membership_sessions_schema_and_attributes_are_rejected(self):
        for field in ('memberships', 'sessions', 'schema_create', 'bad_roles'):
            state = inventory()
            state[field] = 1
            with self.subTest(field=field), self.assertRaises(c2.Stopped):
                c2.validate_inventory(state)

    def test_owner_availability_and_incomplete_inventory_are_rejected(self):
        for field, value in [('owner', 'other_owner'), ('allowconn', False)]:
            state = inventory()
            state['privileges'][0][field] = value
            with self.subTest(field=field), self.assertRaises(c2.Stopped):
                c2.validate_inventory(state)
        state = inventory()
        state['privileges'].pop()
        with self.assertRaises(c2.Stopped):
            c2.validate_inventory(state)

    def test_original_c2_write_phase_unchanged_and_c1_never_created(self):
        source = c2.build_service_sql()
        original = (c2.ROOT / 'scripts/migrations/prepare_catalog_service.sql').read_text(encoding='utf-8')
        marker = 'CREATE ROLE ls_core_catalog_service_dev '
        self.assertEqual(source[source.index(marker):], original[original.index(marker):])
        self.assertNotIn('CREATE ROLE ls_core_catalog_reader_dev', source)
        self.assertNotIn('REVOKE ', source)
        self.assertIn('Expected unassigned, unprivileged C1 reader without ownership', source)
        self.assertIn('Incompatible C1 column ACL', source)

    def test_incompatible_preflight_never_reaches_write_phase(self):
        state = inventory()
        state['memberships'] = 1
        with patch.object(c2, 'docker_identity', return_value={'system_identifier': '7698808171985612839'}), \
                patch.object(c2, 'psql', return_value=json.dumps(state)) as query:
            with self.assertRaises(c2.Stopped):
                c2.prepare_c2_only('unit-test-placeholder')
            self.assertEqual(query.call_count, 1)
            self.assertNotIn('readonly', query.call_args.kwargs)

    def test_success_revalidates_then_creates_only_c2_and_preserves_c1(self):
        replies = [json.dumps(inventory()), '', 'same-c1', '', 'same-c1', '']
        identity = {'system_identifier': '7698808171985612839'}
        with patch.object(c2, 'docker_identity', return_value=identity), patch.object(c2, 'psql', side_effect=replies) as query:
            result = c2.prepare_c2_only('unit-test-placeholder')
            self.assertTrue(result['c1_fingerprint_unchanged'])
            writes = [call for call in query.call_args_list if call.kwargs.get('readonly') is False]
            self.assertEqual(len(writes), 1)
            self.assertEqual(writes[0].kwargs['script'].name, 'prepare_catalog_service.sql')
            self.assertIn('ROLLBACK;', query.call_args_list[1].args[0])


if __name__ == '__main__':
    unittest.main()
