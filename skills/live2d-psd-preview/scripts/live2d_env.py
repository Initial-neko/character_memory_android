"""Read the explicitly authorized .env, without ambient credentials or YAML."""
import json
from pathlib import Path


def load_token(env_file: str | Path) -> str:
    path = Path(env_file)
    if not path.is_file():
        raise ValueError('An explicit .env file is required')
    values = {}
    for raw in path.read_text(encoding='utf-8-sig').splitlines():
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        if line.startswith('export '):
            line = line[7:].lstrip()
        name, separator, value = line.partition('=')
        name = name.strip()
        if not separator or name not in {'MSIMG_API_KEY', 'MODELSCOPE_API_TOKEN'}:
            continue
        value = value.strip()
        if value.startswith('"'):
            try:
                value = json.loads(value)
            except json.JSONDecodeError:
                raise ValueError('Invalid quoted credential in .env') from None
            if not isinstance(value, str):
                raise ValueError('Credential must be a string')
        elif value.startswith("'"):
            if not value.endswith("'") or len(value) < 2:
                raise ValueError('Invalid quoted credential in .env')
            value = value[1:-1]
        values[name] = value.strip()
    token = values.get('MSIMG_API_KEY') or values.get('MODELSCOPE_API_TOKEN')
    if not token or any(character.isspace() for character in token):
        raise ValueError('Set a nonempty MSIMG_API_KEY or MODELSCOPE_API_TOKEN in .env')
    return token
