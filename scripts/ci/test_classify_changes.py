from contextlib import redirect_stdout
import io
import subprocess
import tempfile
import unittest
from unittest.mock import Mock
from pathlib import Path

from scripts.ci.classify_changes import ChangeClassification, classify_changes, collect_git_changes, main


class ChangeClassificationTest(unittest.TestCase):
    def assert_outputs(self, paths, **expected):
        actual = classify_changes(paths, event_type="pull_request").as_outputs()
        defaults = {
            "seed_contracts": False,
            "backend": False,
            "backend_fast": False,
            "backend_contract": False,
            "backend_integration": False,
            "backend_migration": False,
            "backend_slow": False,
            "frontend_quality": False,
            "frontend_storybook": False,
            "frontend_e2e": False,
            "compose": False,
            "run_all": False,
        }
        defaults.update(expected)
        self.assertEqual(actual, defaults)

    def test_markdown_only_runs_documentation_contracts(self):
        self.assert_outputs(["docs/35-test-quality-release-strategy.md"], seed_contracts=True)

    def test_api_contract_runs_documentation_and_backend(self):
        self.assert_outputs(
            ["api/core-api-outline-v1.yaml"],
            seed_contracts=True,
            backend=True,
            backend_contract=True,
        )

    def test_backend_kotlin_runs_backend_only(self):
        self.assert_outputs(
            ["backend/src/main/kotlin/dev/deskseed/Ticket.kt"],
            backend=True,
            backend_fast=True,
            backend_contract=True,
            backend_integration=True,
        )

    def test_backend_domain_change_uses_the_proven_fast_boundary(self):
        self.assert_outputs(
            ["backend/src/main/kotlin/dev/deskseed/ticketing/internal/domain/Ticket.kt"],
            backend=True,
            backend_fast=True,
        )

    def test_backend_worker_change_runs_integration_and_slow(self):
        self.assert_outputs(
            ["backend/src/main/kotlin/dev/deskseed/webhook/internal/WebhookDeliveryWorker.kt"],
            backend=True,
            backend_integration=True,
            backend_slow=True,
        )

    def test_backend_test_infrastructure_runs_every_backend_category_without_compose(self):
        self.assert_outputs(
            ["backend/src/test/kotlin/dev/deskseed/testsupport/integration/DeskseedSpringIntegrationTest.kt"],
            backend=True,
            backend_fast=True,
            backend_contract=True,
            backend_integration=True,
            backend_migration=True,
            backend_slow=True,
        )

    def test_backend_runtime_boot_change_runs_every_backend_category_and_compose(self):
        self.assert_outputs(
            ["backend/src/main/resources/application.yml"],
            backend=True,
            backend_fast=True,
            backend_contract=True,
            backend_integration=True,
            backend_migration=True,
            backend_slow=True,
            compose=True,
        )

    def test_flyway_migration_runs_backend_and_compose(self):
        self.assert_outputs(
            ["backend/src/main/resources/db/migration/V80__example.sql"],
            backend=True,
            backend_integration=True,
            backend_migration=True,
            compose=True,
        )

    def test_frontend_non_visual_logic_runs_quality_only(self):
        self.assert_outputs(["frontend/src/api/client.ts"], frontend_quality=True)

    def test_story_and_design_system_changes_run_storybook(self):
        self.assert_outputs(
            ["frontend/src/design-system/Button.stories.tsx"],
            frontend_quality=True,
            frontend_storybook=True,
        )

    def test_playwright_change_also_runs_frontend_quality(self):
        self.assert_outputs(
            ["frontend/e2e/ticket-workspace.spec.ts"],
            frontend_quality=True,
            frontend_e2e=True,
        )

    def test_compose_change_runs_compose_only(self):
        self.assert_outputs(["compose.yaml"], compose=True)

    def test_workflow_or_unknown_global_file_falls_back_to_all(self):
        for path in [".github/workflows/ci.yml", "unknown-tool-config.toml"]:
            with self.subTest(path=path):
                self.assert_outputs(
                    [path],
                    seed_contracts=True,
                    backend=True,
                    backend_fast=True,
                    backend_contract=True,
                    backend_integration=True,
                    backend_migration=True,
                    backend_slow=True,
                    frontend_quality=True,
                    frontend_storybook=True,
                    frontend_e2e=True,
                    compose=True,
                    run_all=True,
                )

    def test_mixed_pull_request_unions_all_known_impacts(self):
        self.assert_outputs(
            [
                "docs/21-minimum-verification-gates.md",
                "backend/src/test/kotlin/dev/deskseed/TicketTest.kt",
                "frontend/src/features/tickets/ticket.ts",
                "scripts/compose-smoke.sh",
            ],
            seed_contracts=True,
            backend=True,
            backend_fast=True,
            frontend_quality=True,
            compose=True,
        )

    def test_frontend_package_environment_runs_all_frontend_gates(self):
        self.assert_outputs(
            ["frontend/package-lock.json"],
            frontend_quality=True,
            frontend_storybook=True,
            frontend_e2e=True,
        )

    def test_main_push_runs_every_gate(self):
        actual = classify_changes([], event_type="push").as_outputs()
        self.assertEqual(actual, {name: True for name in actual})

    def test_diff_failure_runs_every_gate(self):
        actual = ChangeClassification.fallback("git diff failed").as_outputs()
        self.assertEqual(actual, {name: True for name in actual})

    def test_backend_test_path_rules_match_declared_primary_categories(self):
        repository_root = Path(__file__).resolve().parents[2]
        test_root = repository_root / "backend/src/test/kotlin"
        annotations = {
            "backend_fast": ("category.FastTest", "@FastTest"),
            "backend_contract": ("category.ContractTest", "@ContractTest"),
            "backend_integration": ("category.IntegrationTest", "@IntegrationTest"),
            "backend_migration": ("category.MigrationTest", "@MigrationTest"),
            "backend_slow": ("category.SlowTest", "@SlowTest"),
        }

        checked = 0
        for test_file in sorted(test_root.rglob("*Test.kt")):
            relative = test_file.relative_to(repository_root).as_posix()
            source = test_file.read_text(encoding="utf-8")
            declared = {
                output
                for output, markers in annotations.items()
                if any(marker in source for marker in markers)
            }
            if not declared:
                continue
            checked += 1
            outputs = classify_changes([relative], event_type="pull_request").as_outputs()
            selected = {output for output in annotations if outputs[output]}
            if "/testsupport/" in relative:
                self.assertEqual(selected, set(annotations), relative)
            else:
                self.assertEqual(selected, declared, relative)

        self.assertGreater(checked, 0)


class GitChangeCollectionTest(unittest.TestCase):
    def test_uses_cumulative_base_to_head_diff_including_rename_sources(self):
        completed = subprocess.CompletedProcess(
            args=[],
            returncode=0,
            stdout=(
                b"M\x00backend/src/main/kotlin/dev/deskseed/Ticket.kt\x00"
                b"A\x00docs/final-note.md\x00"
                b"R100\x00backend/src/main/kotlin/dev/deskseed/Old.kt\x00docs/Old.kt.md\x00"
            ),
            stderr=b"",
        )
        runner = Mock(return_value=completed)

        paths = collect_git_changes("a" * 40, "b" * 40, runner=runner)

        self.assertEqual(
            paths,
            [
                "backend/src/main/kotlin/dev/deskseed/Ticket.kt",
                "docs/final-note.md",
                "backend/src/main/kotlin/dev/deskseed/Old.kt",
                "docs/Old.kt.md",
            ],
        )
        command = runner.call_args.args[0]
        self.assertIn(f"{'a' * 40}...{'b' * 40}", command)
        self.assertNotIn(f"{'a' * 40}..{'b' * 40}", command)

    def test_last_commit_documentation_does_not_hide_earlier_backend_change(self):
        completed = subprocess.CompletedProcess(
            args=[],
            returncode=0,
            stdout=(
                b"M\x00backend/src/main/kotlin/dev/deskseed/Ticket.kt\x00"
                b"M\x00docs/final-commit.md\x00"
            ),
            stderr=b"",
        )

        paths = collect_git_changes("1" * 40, "2" * 40, runner=Mock(return_value=completed))
        outputs = classify_changes(paths, event_type="pull_request").as_outputs()

        self.assertTrue(outputs["backend"])
        self.assertTrue(outputs["seed_contracts"])

    def test_git_diff_failure_is_reported_to_fail_closed_caller(self):
        completed = subprocess.CompletedProcess(args=[], returncode=128, stdout=b"", stderr=b"missing base")

        with self.assertRaises(subprocess.CalledProcessError):
            collect_git_changes("1" * 40, "2" * 40, runner=Mock(return_value=completed))


class CommandLineContractTest(unittest.TestCase):
    def test_github_outputs_are_always_lowercase_booleans_and_summary_escapes_controls(self):
        with tempfile.TemporaryDirectory() as directory:
            output_path = Path(directory) / "output"
            summary_path = Path(directory) / "summary"

            with redirect_stdout(io.StringIO()):
                exit_code = main(
                    [
                        "--event-type",
                        "pull_request",
                        "--changed-file",
                        "docs/note\n## injected.md",
                        "--github-output",
                        str(output_path),
                        "--github-step-summary",
                        str(summary_path),
                    ],
                )

            self.assertEqual(exit_code, 0)
            output_lines = output_path.read_text(encoding="utf-8").splitlines()
            self.assertTrue(output_lines)
            self.assertTrue(all(line.endswith(("=true", "=false")) for line in output_lines))
            summary = summary_path.read_text(encoding="utf-8")
            self.assertIn(r"docs/note\x0a## injected.md", summary)
            self.assertNotIn("\n## injected.md", summary)


if __name__ == "__main__":
    unittest.main()
