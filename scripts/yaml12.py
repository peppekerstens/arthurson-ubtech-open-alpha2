"""
YAML loader with the YAML 1.2 rule for booleans (alpha2-brain#29).

PyYAML follows YAML 1.1: the plain words on, off, yes, no, y and n load as booleans.
The OpenAPI file has query parameters named `on`. With yaml.safe_load the name became
True, and the generator wrote `params.True` in api-client.js. This loader accepts only
true and false (any case) as booleans, as YAML 1.2 does, and the browser docs (js-yaml).
"""
import re

import yaml


class Yaml12Loader(yaml.SafeLoader):
    pass


# Remove the YAML 1.1 bool resolver and add the YAML 1.2 one.
Yaml12Loader.yaml_implicit_resolvers = {
    k: [(tag, rx) for tag, rx in v if tag != "tag:yaml.org,2002:bool"]
    for k, v in yaml.SafeLoader.yaml_implicit_resolvers.items()
}
Yaml12Loader.add_implicit_resolver(
    "tag:yaml.org,2002:bool",
    re.compile(r"^(?:true|True|TRUE|false|False|FALSE)$"),
    list("tTfF"),
)


def load(stream):
    """Like yaml.safe_load, with YAML 1.2 booleans."""
    return yaml.load(stream, Loader=Yaml12Loader)
