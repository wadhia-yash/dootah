import random
import string
import tempfile
import unittest
from pathlib import Path
import validate


class DocumentationTests(unittest.TestCase):
    def test_missing_file_and_heading_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            page = root / 'README.md'
            page.write_text('# Good heading\n')
            self.assertFalse(validate.link_errors(page, '[ok](#good-heading)', root))
            self.assertTrue(validate.link_errors(page, '[bad](missing.md)', root))
            self.assertTrue(validate.link_errors(page, '[bad](#missing)', root))
            self.assertTrue(validate.link_errors(page, '[escape](../outside.md)', root))

    def test_stale_values_paths_and_claims_refused(self):
        for text in ['SDK 0.3.0-local', 'Runtime ABI **1**', 'Logic ABI 2',
                     '/Users/example/work', '/home/developer/work', 'zero runtime overhead',
                     'DootahActivity required']:
            self.assertTrue(validate.prose_errors('example.md', text, {'0.1.0-alpha.1', '0.7.5-local'}), text)
        self.assertFalse(validate.prose_errors('example.md', 'Runtime ABI **2**, Logic ABI **1**, SDK 0.1.0-alpha.1', {'0.1.0-alpha.1'}))

    def test_home_paths_detected_for_any_user_name(self):
        name = 'u' + ''.join(random.choices(string.ascii_lowercase + string.digits, k=10))
        for text in [f'/Users/{name}/Documents/repo', f'/home/{name}/repo', f'C:\\Users\\{name}\\repo',
                     '~/Documents/repo', '/var/folders/ab/T/x']:
            self.assertTrue(validate.HOME.search(text), text)
            self.assertTrue(validate.prose_errors('example.md', text, set()), text)
        for text in ['/Users/<you>/repo', '/home/<user>/repo', '~/.dootah-v2/server-state', '$HOME/.dootah-v2',
                     '/tmp/dootah-v2-phase3']:
            self.assertFalse(validate.HOME.search(text), text)

    def test_cli_unknown_command_subcommand_and_flag_refused(self):
        surface = validate.cli_surface()
        self.assertFalse(validate.cli_errors('x', '```sh\ndootah release inspect --id UUID\n```', surface))
        for command in ['dootah launch', 'dootah release erase --id UUID', 'dootah rollback --made-up yes']:
            self.assertTrue(validate.cli_errors('x', '```sh\n' + command + '\n```', surface))

    def test_duplicate_heading_fragments(self):
        self.assertEqual({'title', 'title-1'}, validate.anchors('# Title\n# Title\n```sh\n# Not a heading\n```'))


if __name__ == '__main__':
    unittest.main()
