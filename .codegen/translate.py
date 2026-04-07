#!/usr/bin/env python3
"""
translate.py — Upstream Python MCP tools → Java McpTool classes

Parses all Confluence tool definitions from the upstream mcp-atlassian Python project
and generates Java tool classes for the confluence-mcp-plugin.

Usage:
    python3 .codegen/translate.py

Reads:  .upstream/mcp-atlassian/src/mcp_atlassian/servers/confluence.py
Writes: .codegen/generated/tools/{package}/{ClassName}Tool.java
        .codegen/generated/ToolRegistry_fragment.java
        .codegen/report.txt
"""

import ast
import json
import os
import re
import textwrap
from dataclasses import dataclass, field
from pathlib import Path

# ─── Paths ────────────────────────────────────────────────────────────────────

ROOT = Path(__file__).resolve().parent.parent
UPSTREAM_CONFLUENCE_PY = ROOT / ".upstream/mcp-atlassian/src/mcp_atlassian/servers/confluence.py"
OUTPUT_DIR = ROOT / ".codegen/generated/tools"
REPORT_PATH = ROOT / ".codegen/generated/report.txt"
REGISTRY_PATH = ROOT / ".codegen/generated/ToolRegistry_fragment.java"
EXISTING_TOOLS_DIR = ROOT / "src/main/java/com/atlassian/mcp/plugin/tools"

JAVA_PACKAGE_BASE = "com.atlassian.mcp.plugin.tools"

# Java reserved words that can't be used as variable names
JAVA_RESERVED = {
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
    "class", "const", "continue", "default", "do", "double", "else", "enum",
    "extends", "final", "finally", "float", "for", "goto", "if", "implements",
    "import", "instanceof", "int", "interface", "long", "native", "new",
    "package", "private", "protected", "public", "return", "short", "static",
    "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
    "transient", "try", "void", "volatile", "while",
}

# ─── Known constants from upstream ────────────────────────────────────────────

# Confluence has no complex constants like Jira's ISSUE_KEY_PATTERN
KNOWN_CONSTANTS = {}

# ─── Toolset → Java package mapping ──────────────────────────────────────────

TOOLSET_TO_PACKAGE = {
    "confluence_pages": "pages",
    "confluence_comments": "comments",
    "confluence_labels": "labels",
    "confluence_users": "users",
    "confluence_analytics": "analytics",
    "confluence_attachments": "attachments",
}

# Toolset → requiredPluginKey (null means no requirement)
# Confluence tools don't require optional plugins — all are core
TOOLSET_TO_PLUGIN_KEY = {}

# Python type name → JSON Schema type
PYTHON_TO_JSON = {
    "str": "string",
    "int": "integer",
    "bool": "boolean",
    "float": "number",
    "dict": "string",  # We send dicts as JSON strings in Java
    "list": "string",
}

# ─── REST API endpoint mapping ────────────────────────────────────────────────
# Each entry: method, path_template, query_params
# path params use {param_name} placeholders
# query_params maps REST query param → Python param name

REST_MAP = {
    # Pages — search
    "search": ("GET", "/rest/api/content/search", {"cql": "query", "limit": "limit"}),
    # Pages — CRUD
    "get_page": ("GET", "/rest/api/content/{page_id}", {"expand": "_expand"}),
    "get_page_children": ("GET", "/rest/api/content/{parent_id}/child/page", {"expand": "expand", "limit": "limit", "start": "start"}),
    "create_page": ("POST", "/rest/api/content", {}),
    "update_page": ("PUT", "/rest/api/content/{page_id}", {}),
    "delete_page": ("DELETE", "/rest/api/content/{page_id}", {}),
    "move_page": ("PUT", "/rest/api/content/{page_id}/move/{position}/{target_parent_id}", {}),
    "get_page_history": ("GET", "/rest/api/content/{page_id}", {"status": "_status", "version": "version", "expand": "_expand"}),
    # get_page_diff is computed client-side (fetch two versions, diff in Java) — no single REST call
    # Comments
    "get_comments": ("GET", "/rest/api/content/{page_id}/child/comment", {"expand": "_expand"}),
    "add_comment": ("POST", "/rest/api/content", {}),
    "reply_to_comment": ("POST", "/rest/api/content", {}),
    # Labels
    "get_labels": ("GET", "/rest/api/content/{page_id}/label", {}),
    "add_label": ("POST", "/rest/api/content/{page_id}/label", {}),
    # Users
    "search_user": ("GET", "/rest/api/group/{group_name}/member", {"limit": "limit"}),
    # Analytics — Cloud-only, may not work on DC
    "get_page_views": ("GET", "/rest/api/analytics/content/{page_id}/views", {}),
    # Attachments
    "get_attachments": ("GET", "/rest/api/content/{content_id}/child/attachment", {"start": "start", "limit": "limit", "filename": "filename", "mediaType": "media_type"}),
    "upload_attachment": ("POST", "/rest/api/content/{content_id}/child/attachment", {}),
    "delete_attachment": ("DELETE", "/rest/api/content/{attachment_id}", {}),
    # download_attachment, download_content_attachments, upload_attachments, get_page_images
    # are complex (binary I/O, multi-step) — generate TODO stubs
}


# ─── Data Models ──────────────────────────────────────────────────────────────

@dataclass
class ToolParam:
    name: str
    json_type: str      # "string", "integer", "boolean"
    description: str
    required: bool
    default: object = None      # None, int, str, bool
    pattern: str | None = None
    ge: int | None = None
    le: int | None = None


@dataclass
class ToolDef:
    name: str               # snake_case function/tool name
    title: str              # Human-readable title from annotations
    description: str        # First line of docstring
    params: list[ToolParam] = field(default_factory=list)
    is_write: bool = False
    toolset: str = ""       # e.g. "confluence_pages"


# ─── AST Helpers ──────────────────────────────────────────────────────────────

def _is_confluence_mcp_tool(node: ast.expr) -> bool:
    """Check if a node is `confluence_mcp.tool`."""
    return (
        isinstance(node, ast.Attribute)
        and node.attr == "tool"
        and isinstance(node.value, ast.Name)
        and node.value.id == "confluence_mcp"
    )


def _extract_set_literal(node: ast.expr) -> set[str]:
    """Extract a set literal like {"confluence", "read", "toolset:confluence_pages"}."""
    result = set()
    if isinstance(node, ast.Set):
        for elt in node.elts:
            if isinstance(elt, ast.Constant) and isinstance(elt.value, str):
                result.add(elt.value)
    return result


def _extract_dict_literal(node: ast.expr) -> dict:
    """Extract a dict literal like {"title": "Get Page", "readOnlyHint": True}."""
    result = {}
    if isinstance(node, ast.Dict):
        for key, val in zip(node.keys, node.values):
            if isinstance(key, ast.Constant):
                result[key.value] = _ast_to_value(val)
    return result


def _ast_to_value(node: ast.expr | None) -> object:
    """Convert an AST constant node to a Python value. Best-effort."""
    if node is None:
        return None
    if isinstance(node, ast.Constant):
        return node.value
    if isinstance(node, ast.Name):
        # Resolve known constants
        if node.id in KNOWN_CONSTANTS:
            return KNOWN_CONSTANTS[node.id]
        if node.id == "None":
            return None
        if node.id == "True":
            return True
        if node.id == "False":
            return False
        return f"<{node.id}>"
    if isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.USub):
        val = _ast_to_value(node.operand)
        if isinstance(val, (int, float)):
            return -val
    # Handle List literals
    if isinstance(node, ast.List):
        return [_ast_to_value(e) for e in node.elts]
    return None


def _extract_type(node: ast.expr) -> tuple[str, bool]:
    """Extract JSON Schema type from a Python type annotation.

    Returns (json_type, is_nullable).
    """
    # Simple name: str, int, bool
    if isinstance(node, ast.Name):
        return PYTHON_TO_JSON.get(node.id, "string"), False

    # str | None  →  BinOp(left, BitOr, right)
    if isinstance(node, ast.BinOp) and isinstance(node.op, ast.BitOr):
        # Check left and right for None
        if isinstance(node.right, ast.Constant) and node.right.value is None:
            left_type, _ = _extract_type(node.left)
            return left_type, True
        if isinstance(node.left, ast.Constant) and node.left.value is None:
            right_type, _ = _extract_type(node.right)
            return right_type, True
        # Neither is None; could be dict | str → treat as string
        left_type, _ = _extract_type(node.left)
        return left_type, False

    # Subscript: dict[str, Any], list[str], etc.
    if isinstance(node, ast.Subscript):
        if isinstance(node.value, ast.Name):
            return PYTHON_TO_JSON.get(node.value.id, "string"), False

    return "string", False


def _parse_field_call(node: ast.expr) -> dict:
    """Parse a Field(...) call and extract keyword arguments."""
    kwargs = {}
    if not isinstance(node, ast.Call):
        return kwargs
    # Verify it's a Field call
    if isinstance(node.func, ast.Name) and node.func.id != "Field":
        return kwargs
    if isinstance(node.func, ast.Attribute) and node.func.attr != "Field":
        return kwargs

    for kw in node.keywords:
        if kw.arg in ("description", "pattern", "default", "ge", "le"):
            val = _ast_to_value(kw.value)
            # For description, handle string concatenation
            if kw.arg == "description" and isinstance(kw.value, ast.JoinedStr):
                val = "<f-string>"
            if kw.arg == "description" and val is None:
                # Try to extract from a parenthesized string concat
                val = _extract_string_concat(kw.value)
            kwargs[kw.arg] = val
    return kwargs


def _extract_string_concat(node: ast.expr) -> str | None:
    """Extract concatenated string constants (paren-wrapped multiline strings)."""
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        return node.value
    return _ast_to_value(node) if isinstance(_ast_to_value(node), str) else None


def _parse_annotation(node: ast.expr) -> tuple[str, bool, dict]:
    """Parse a parameter annotation like Annotated[str, Field(...)].

    Returns (json_type, is_nullable, field_kwargs).
    """
    # Annotated[Type, Field(...)]
    if isinstance(node, ast.Subscript):
        # Check if it's Annotated
        if isinstance(node.value, ast.Name) and node.value.id == "Annotated":
            slice_node = node.slice
            if isinstance(slice_node, ast.Tuple) and len(slice_node.elts) >= 2:
                type_node = slice_node.elts[0]
                field_node = slice_node.elts[1]

                json_type, is_nullable = _extract_type(type_node)
                field_kwargs = _parse_field_call(field_node)

                return json_type, is_nullable, field_kwargs

    # Plain type (str, int, etc.)
    json_type, is_nullable = _extract_type(node)
    return json_type, is_nullable, {}


# ─── Main Parser ─────────────────────────────────────────────────────────────

def parse_upstream(source_path: Path) -> list[ToolDef]:
    """Parse confluence.py and extract all tool definitions."""
    source = source_path.read_text()
    tree = ast.parse(source)

    tools: list[ToolDef] = []

    for node in ast.iter_child_nodes(tree):
        if not isinstance(node, ast.AsyncFunctionDef):
            continue

        # Find @confluence_mcp.tool(...) decorator
        tool_decorator = None
        has_write_decorator = False
        for dec in node.decorator_list:
            if isinstance(dec, ast.Call) and _is_confluence_mcp_tool(dec.func):
                tool_decorator = dec
            if isinstance(dec, ast.Name) and dec.id == "check_write_access":
                has_write_decorator = True

        if tool_decorator is None:
            continue

        # Extract tags and annotations from decorator kwargs
        tags: set[str] = set()
        annotations: dict = {}
        for kw in tool_decorator.keywords:
            if kw.arg == "tags":
                tags = _extract_set_literal(kw.value)
            elif kw.arg == "annotations":
                annotations = _extract_dict_literal(kw.value)

        is_write = "write" in tags or has_write_decorator
        toolset = ""
        for tag in tags:
            if tag.startswith("toolset:"):
                toolset = tag.split(":", 1)[1]

        title = annotations.get("title", "")

        # Extract description from docstring (everything before Args:/Returns:/Raises: sections)
        docstring = ast.get_docstring(node) or ""
        desc_text = docstring.strip()
        for marker in ("\n    Args:", "\n    Returns:", "\n    Raises:", "\nArgs:", "\nReturns:", "\nRaises:"):
            idx = desc_text.find(marker)
            if idx != -1:
                desc_text = desc_text[:idx]
                break
        first_line = " ".join(desc_text.split()).strip()

        # Extract parameters (skip 'ctx')
        params = _parse_function_params(node)

        tools.append(ToolDef(
            name=node.name,
            title=title,
            description=first_line,
            params=params,
            is_write=is_write,
            toolset=toolset,
        ))

    return tools


def _parse_function_params(node: ast.AsyncFunctionDef) -> list[ToolParam]:
    """Extract tool parameters from function signature, skipping 'ctx'."""
    params: list[ToolParam] = []
    args = node.args

    num_args = len(args.args)
    num_defaults = len(args.defaults)
    first_default_idx = num_args - num_defaults

    for i, arg in enumerate(args.args):
        if arg.arg == "ctx" or arg.arg == "self":
            continue
        if arg.annotation is None:
            continue

        # Check if this param has a default value
        default_idx = i - first_default_idx
        has_default = default_idx >= 0
        func_default = args.defaults[default_idx] if has_default else None

        # Parse annotation
        json_type, is_nullable, field_kwargs = _parse_annotation(arg.annotation)

        # Get description
        description = field_kwargs.get("description", "")
        if isinstance(description, str):
            # Clean up multiline descriptions: collapse to single line for Java
            description = " ".join(description.split())
        else:
            description = str(description) if description else ""

        pattern = field_kwargs.get("pattern")
        ge = field_kwargs.get("ge")
        le = field_kwargs.get("le")

        # Determine default value
        field_default = field_kwargs.get("default")
        effective_default = field_default
        if effective_default is None and has_default:
            effective_default = _ast_to_value(func_default)

        # Required: no default AND not nullable
        required = not has_default and not is_nullable

        params.append(ToolParam(
            name=arg.arg,
            json_type=json_type,
            description=description,
            required=required,
            default=effective_default,
            pattern=pattern if isinstance(pattern, str) else None,
            ge=int(ge) if ge is not None and isinstance(ge, (int, float)) else None,
            le=int(le) if le is not None and isinstance(le, (int, float)) else None,
        ))

    return params


# ─── Java Code Generator ─────────────────────────────────────────────────────

def to_class_name(tool_name: str) -> str:
    """Convert snake_case tool name to PascalCase class name + 'Tool'.

    Examples: get_page → GetPageTool, search → SearchTool
    """
    parts = tool_name.split("_")
    pascal = "".join(p.capitalize() for p in parts)
    return pascal + "Tool"


def to_java_package(toolset: str) -> str:
    """Map toolset to Java sub-package name."""
    return TOOLSET_TO_PACKAGE.get(toolset, "misc")


def get_plugin_key(toolset: str) -> str | None:
    """Get the required plugin key for a toolset, or None."""
    return TOOLSET_TO_PLUGIN_KEY.get(toolset)


def escape_java_string(s: str) -> str:
    """Escape a string for use in a Java string literal."""
    return (s
            .replace("\\", "\\\\")
            .replace('"', '\\"')
            .replace("\n", "\\n")
            .replace("\t", "\\t"))


def generate_property_map(param: ToolParam) -> str:
    """Generate a Map.of(...) expression for a single property in inputSchema."""
    entries = [f'"type", "{param.json_type}"']

    desc = escape_java_string(param.description)
    entries.append(f'"description", "{desc}"')

    if param.default is not None:
        if isinstance(param.default, bool):
            entries.append(f'"default", {"true" if param.default else "false"}')
        elif isinstance(param.default, int):
            entries.append(f'"default", {param.default}')
        elif isinstance(param.default, str):
            entries.append(f'"default", "{escape_java_string(param.default)}"')

    return f"Map.of({', '.join(entries)})"


def generate_input_schema(tool: ToolDef) -> str:
    """Generate the inputSchema() method body."""
    if not tool.params:
        return '        return Map.of(\n                "type", "object",\n                "properties", Map.of(),\n                "required", List.of()\n        );'

    # Required params
    required_params = [p for p in tool.params if p.required]
    required_str = ", ".join(f'"{p.name}"' for p in required_params)

    # Use Map.ofEntries if > 10 properties
    if len(tool.params) > 10:
        entry_lines = []
        for p in tool.params:
            prop_map = generate_property_map(p)
            entry_lines.append(f'                        Map.entry("{p.name}", {prop_map})')
        entries_str = ",\n".join(entry_lines)
        return (
            f'        return Map.of(\n'
            f'                "type", "object",\n'
            f'                "properties", Map.ofEntries(\n'
            f'{entries_str}\n'
            f'                ),\n'
            f'                "required", List.of({required_str})\n'
            f'        );'
        )

    prop_lines = []
    for p in tool.params:
        prop_map = generate_property_map(p)
        prop_lines.append(f'                        "{p.name}", {prop_map}')
    props_str = ",\n".join(prop_lines)

    return (
        f'        return Map.of(\n'
        f'                "type", "object",\n'
        f'                "properties", Map.of(\n'
        f'{props_str}\n'
        f'                ),\n'
        f'                "required", List.of({required_str})\n'
        f'        );'
    )


def generate_execute_body(tool: ToolDef) -> str:
    """Generate the execute() method body based on REST API mapping."""
    mapping = REST_MAP.get(tool.name)
    if not mapping:
        return _generate_todo_execute(tool)

    http_method, path_template, query_map = mapping

    # Identify path parameters (e.g., {page_id})
    path_params = re.findall(r"\{(\w+)\}", path_template)

    lines = []

    # 1. Extract and validate params
    for p in tool.params:
        var = _to_camel(p.name)
        if p.required:
            if p.json_type == "integer":
                cap = f"Math.min(getInt(args, \"{p.name}\", 0), {p.le})" if p.le else f"getInt(args, \"{p.name}\", 0)"
                lines.append(f"        int {var} = {cap};")
            elif p.json_type == "boolean":
                default_val = "true" if p.default else "false"
                lines.append(f"        boolean {var} = getBoolean(args, \"{p.name}\", {default_val});")
            else:
                lines.append(f'        String {var} = (String) args.get("{p.name}");')
                lines.append(f"        if ({var} == null || {var}.isBlank()) {{")
                lines.append(f"            throw new McpToolException(\"'{p.name}' parameter is required\");")
                lines.append(f"        }}")
        else:
            if p.json_type == "integer":
                default_val = p.default if isinstance(p.default, int) else 0
                cap = f"Math.min(getInt(args, \"{p.name}\", {default_val}), {p.le})" if p.le else f"getInt(args, \"{p.name}\", {default_val})"
                lines.append(f"        int {var} = {cap};")
            elif p.json_type == "boolean":
                default_val = "true" if p.default else "false"
                lines.append(f"        boolean {var} = getBoolean(args, \"{p.name}\", {default_val});")
            else:
                if p.default and isinstance(p.default, str):
                    default_escaped = escape_java_string(p.default)
                    lines.append(f'        String {var} = (String) args.getOrDefault("{p.name}", "{default_escaped}");')
                else:
                    lines.append(f'        String {var} = (String) args.get("{p.name}");')

    lines.append("")

    # 2. Build path with substitutions
    def _build_java_path(template: str) -> str:
        jp = template
        for pp in path_params:
            jp = jp.replace(f"{{{pp}}}", f'" + {_to_camel(pp)} + "')
        jp = f'"{jp}"'
        return jp.replace(' + ""', "").replace('"" + ', "")

    java_path = _build_java_path(path_template)

    # 3. Build the request by HTTP method
    if http_method == "GET":
        if query_map:
            non_path = {qp: tp for qp, tp in query_map.items() if tp not in path_params and not tp.startswith("_")}
            internal = {qp: tp for qp, tp in query_map.items() if tp.startswith("_")}
            if non_path or internal:
                lines.append("        StringBuilder query = new StringBuilder();")
                lines.append('        String sep = "?";')
                for query_key, param_name in non_path.items():
                    camel = _to_camel(param_name)
                    param_def = next((p for p in tool.params if p.name == param_name), None)
                    if param_def and param_def.json_type in ("integer", "boolean"):
                        lines.append(f'        query.append(sep).append("{query_key}=").append({camel});')
                        lines.append('        sep = "&";')
                    else:
                        lines.append(f"        if ({camel} != null && !{camel}.isBlank()) {{")
                        lines.append(f'            query.append(sep).append("{query_key}=").append(encode({camel}));')
                        lines.append(f'            sep = "&";')
                        lines.append(f"        }}")
                lines.append("")
                lines.append(f"        return client.get({java_path} + query, authHeader);")
            else:
                lines.append(f"        return client.get({java_path}, authHeader);")
        else:
            lines.append(f"        return client.get({java_path}, authHeader);")

    elif http_method in ("POST", "PUT"):
        client_method = "client.post" if http_method == "POST" else "client.put"
        body_params = [p for p in tool.params if p.name not in path_params]
        if body_params:
            lines.append("        Map<String, Object> requestBody = new HashMap<>();")
            for bp in body_params:
                var = _to_camel(bp.name)
                if bp.json_type == "boolean":
                    lines.append(f'        requestBody.put("{bp.name}", {var});')
                elif bp.required:
                    lines.append(f'        requestBody.put("{bp.name}", {var});')
                else:
                    lines.append(f"        if ({var} != null) requestBody.put(\"{bp.name}\", {var});")
            lines.append("        try {")
            lines.append("            String jsonBody = mapper.writeValueAsString(requestBody);")
            lines.append(f"            return {client_method}({java_path}, jsonBody, authHeader);")
            lines.append("        } catch (Exception e) {")
            lines.append('            throw new McpToolException("Failed to serialize request: " + e.getMessage());')
            lines.append("        }")
        else:
            lines.append(f'        return {client_method}({java_path}, "{{}}", authHeader);')

    elif http_method == "DELETE":
        if query_map:
            lines.append("        StringBuilder query = new StringBuilder();")
            lines.append('        String sep = "?";')
            for query_key, param_name in query_map.items():
                camel = _to_camel(param_name)
                lines.append(f"        if ({camel} != null && !{camel}.isBlank()) {{")
                lines.append(f'            query.append(sep).append("{query_key}=").append(encode({camel}));')
                lines.append(f'            sep = "&";')
                lines.append(f"        }}")
            lines.append(f"        return client.delete({java_path} + query, authHeader);")
        else:
            lines.append(f"        return client.delete({java_path}, authHeader);")

    return "\n".join(lines)


def _generate_todo_execute(tool: ToolDef) -> str:
    """Generate a placeholder execute body for unmapped tools."""
    return textwrap.dedent(f"""\
        // TODO: Implement REST API call for {tool.name}
        throw new McpToolException("Tool '{tool.name}' not yet implemented");""")


def _to_camel(snake: str) -> str:
    """Convert snake_case to camelCase, avoiding Java reserved words."""
    parts = snake.split("_")
    result = parts[0] + "".join(p.capitalize() for p in parts[1:])
    if result in JAVA_RESERVED:
        return f"_{result}"
    return result


def needs_mapper(tool: ToolDef) -> bool:
    """Check if this tool needs a Jackson ObjectMapper."""
    mapping = REST_MAP.get(tool.name)
    if not mapping:
        return False
    method = mapping[0]
    path_params = re.findall(r"\{(\w+)\}", mapping[1])
    body_params = [p for p in tool.params if p.name not in path_params]
    return method in ("POST", "PUT") and len(body_params) > 0


def needs_url_encoder(tool: ToolDef) -> bool:
    """Check if this tool needs URLEncoder."""
    mapping = REST_MAP.get(tool.name)
    if not mapping:
        return False
    method = mapping[0]
    return method == "GET" and bool(mapping[2])


def needs_int_helper(tool: ToolDef) -> bool:
    """Check if this tool needs the getInt helper."""
    return any(p.json_type == "integer" for p in tool.params)


def needs_bool_helper(tool: ToolDef) -> bool:
    """Check if this tool needs the getBoolean helper."""
    return any(p.json_type == "boolean" for p in tool.params)


def generate_java_class(tool: ToolDef) -> str:
    """Generate a complete Java tool class."""
    class_name = to_class_name(tool.name)
    package = to_java_package(tool.toolset)
    full_package = f"{JAVA_PACKAGE_BASE}.{package}"
    plugin_key = get_plugin_key(tool.toolset)

    use_mapper = needs_mapper(tool)
    use_encoder = needs_url_encoder(tool)
    use_int_helper = needs_int_helper(tool)
    use_bool_helper = needs_bool_helper(tool)

    # Build imports
    imports = [
        f"package {full_package};",
        "",
        "import com.atlassian.mcp.plugin.ConfluenceRestClient;",
        "import com.atlassian.mcp.plugin.McpToolException;",
        "import com.atlassian.mcp.plugin.tools.McpTool;",
        "",
    ]

    extra_imports = set()
    if use_mapper:
        extra_imports.add("import com.fasterxml.jackson.databind.ObjectMapper;")
        extra_imports.add("import java.util.HashMap;")
    if use_encoder:
        extra_imports.add("import java.net.URLEncoder;")
        extra_imports.add("import java.nio.charset.StandardCharsets;")
    extra_imports.add("import java.util.List;")
    extra_imports.add("import java.util.Map;")

    for imp in sorted(extra_imports):
        imports.append(imp)

    imports.append("")

    # Description
    desc_escaped = escape_java_string(tool.description)

    # inputSchema
    schema_body = generate_input_schema(tool)

    # execute body
    execute_body = generate_execute_body(tool)

    # Build class
    lines = imports.copy()
    lines.append(f"public class {class_name} implements McpTool {{")
    lines.append(f"    private final ConfluenceRestClient client;")
    if use_mapper:
        lines.append(f"    private final ObjectMapper mapper = new ObjectMapper();")
    lines.append("")
    lines.append(f"    public {class_name}(ConfluenceRestClient client) {{")
    lines.append(f"        this.client = client;")
    lines.append(f"    }}")
    lines.append("")

    # name()
    lines.append(f'    @Override public String name() {{ return "{tool.name}"; }}')
    lines.append("")

    # description()
    lines.append(f"    @Override")
    lines.append(f"    public String description() {{")
    lines.append(f'        return "{desc_escaped}";')
    lines.append(f"    }}")
    lines.append("")

    # inputSchema()
    lines.append(f"    @Override")
    lines.append(f"    public Map<String, Object> inputSchema() {{")
    for schema_line in schema_body.split("\n"):
        lines.append(schema_line)
    lines.append(f"    }}")
    lines.append("")

    # isWriteTool()
    lines.append(f"    @Override public boolean isWriteTool() {{ return {str(tool.is_write).lower()}; }}")
    lines.append("")

    # requiredPluginKey() override
    if plugin_key:
        lines.append(f"    @Override")
        lines.append(f'    public String requiredPluginKey() {{ return "{plugin_key}"; }}')
        lines.append("")

    # execute()
    lines.append(f"    @Override")
    lines.append(f"    public String execute(Map<String, Object> args, String authHeader) throws McpToolException {{")
    for exec_line in execute_body.split("\n"):
        lines.append(exec_line)
    lines.append(f"    }}")

    # Helper methods
    if use_encoder:
        lines.append("")
        lines.append("    private static String encode(String s) {")
        lines.append("        return URLEncoder.encode(s, StandardCharsets.UTF_8);")
        lines.append("    }")

    if use_int_helper:
        lines.append("")
        lines.append("    private static int getInt(Map<String, Object> args, String key, int defaultVal) {")
        lines.append("        Object val = args.get(key);")
        lines.append("        if (val instanceof Number n) return n.intValue();")
        lines.append("        if (val instanceof String s) {")
        lines.append("            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return defaultVal; }")
        lines.append("        }")
        lines.append("        return defaultVal;")
        lines.append("    }")

    if use_bool_helper:
        lines.append("")
        lines.append("    private static boolean getBoolean(Map<String, Object> args, String key, boolean defaultVal) {")
        lines.append("        Object val = args.get(key);")
        lines.append("        if (val instanceof Boolean b) return b;")
        lines.append('        if (val instanceof String s) return "true".equalsIgnoreCase(s);')
        lines.append("        return defaultVal;")
        lines.append("    }")

    lines.append("}")
    lines.append("")

    return "\n".join(lines)


# ─── Report Generator ────────────────────────────────────────────────────────

def find_existing_tools(tools_dir: Path) -> dict[str, Path]:
    """Find existing Java tool files and extract their tool names."""
    result = {}
    if not tools_dir.exists():
        return result

    for java_file in tools_dir.rglob("*Tool.java"):
        # Read the file and extract the name() return value
        content = java_file.read_text()
        match = re.search(r'public String name\(\)\s*\{.*?return\s+"([^"]+)"', content, re.DOTALL)
        if match:
            result[match.group(1)] = java_file
    return result


def generate_report(upstream_tools: list[ToolDef], existing: dict[str, Path]) -> str:
    """Generate a comparison report."""
    lines = []
    lines.append("=" * 70)
    lines.append("UPSTREAM → JAVA TOOL PARITY REPORT")
    lines.append("=" * 70)
    lines.append("")
    lines.append(f"Upstream tools:  {len(upstream_tools)}")
    lines.append(f"Existing Java:   {len(existing)}")
    lines.append("")

    # Missing tools
    upstream_names = {t.name for t in upstream_tools}
    existing_names = set(existing.keys())
    missing = upstream_names - existing_names
    extra = existing_names - upstream_names

    if missing:
        lines.append("MISSING TOOLS (in upstream but not in Java):")
        for name in sorted(missing):
            tool = next(t for t in upstream_tools if t.name == name)
            lines.append(f"  - {name} ({tool.toolset}) {'[WRITE]' if tool.is_write else '[READ]'}")
        lines.append("")

    if extra:
        lines.append("EXTRA TOOLS (in Java but not in upstream):")
        for name in sorted(extra):
            lines.append(f"  - {name} ({existing[name].relative_to(EXISTING_TOOLS_DIR)})")
        lines.append("")

    # Tool-by-tool parameter comparison
    lines.append("-" * 70)
    lines.append("TOOL DETAILS (all upstream tools)")
    lines.append("-" * 70)
    for tool in sorted(upstream_tools, key=lambda t: t.name):
        status = "EXISTS" if tool.name in existing_names else "MISSING"
        lines.append(f"\n{tool.name} [{status}] ({tool.toolset})")
        lines.append(f"  Title:       {tool.title}")
        lines.append(f"  Description: {tool.description[:80]}{'...' if len(tool.description) > 80 else ''}")
        lines.append(f"  Write:       {tool.is_write}")
        lines.append(f"  Params ({len(tool.params)}):")
        for p in tool.params:
            req = "REQUIRED" if p.required else "optional"
            default = f" default={p.default}" if p.default is not None else ""
            lines.append(f"    - {p.name}: {p.json_type} ({req}{default})")

    lines.append("")
    lines.append("=" * 70)
    lines.append(f"Total: {len(upstream_tools)} tools, {len(missing)} missing, {len(extra)} extra")
    lines.append("=" * 70)
    return "\n".join(lines)


def generate_registry_fragment(tools: list[ToolDef]) -> str:
    """Generate the ToolRegistry registerAllTools() method body."""
    lines = []
    lines.append("    // Auto-generated tool registration — paste into ToolRegistry.registerAllTools()")
    lines.append("    private void registerAllTools(ConfluenceRestClient client) {")

    # Group by package
    by_package: dict[str, list[ToolDef]] = {}
    for tool in tools:
        pkg = to_java_package(tool.toolset)
        by_package.setdefault(pkg, []).append(tool)

    for pkg in sorted(by_package.keys()):
        lines.append(f"        // {pkg}")
        for tool in sorted(by_package[pkg], key=lambda t: t.name):
            class_name = to_class_name(tool.name)
            lines.append(f"        register(new {class_name}(client));")
        lines.append("")

    lines.append("    }")
    return "\n".join(lines)


# ─── Main ─────────────────────────────────────────────────────────────────────

def main():
    if not UPSTREAM_CONFLUENCE_PY.exists():
        print(f"ERROR: Upstream file not found: {UPSTREAM_CONFLUENCE_PY}")
        return 1

    print(f"Parsing {UPSTREAM_CONFLUENCE_PY.relative_to(ROOT)} ...")
    tools = parse_upstream(UPSTREAM_CONFLUENCE_PY)
    print(f"Found {len(tools)} tool definitions\n")

    # Generate Java files
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    for tool in tools:
        pkg = to_java_package(tool.toolset)
        pkg_dir = OUTPUT_DIR / pkg
        pkg_dir.mkdir(parents=True, exist_ok=True)

        class_name = to_class_name(tool.name)
        java_path = pkg_dir / f"{class_name}.java"

        java_source = generate_java_class(tool)
        java_path.write_text(java_source)
        print(f"  Generated {pkg}/{class_name}.java")

    # Generate registry fragment
    registry_src = generate_registry_fragment(tools)
    REGISTRY_PATH.parent.mkdir(parents=True, exist_ok=True)
    REGISTRY_PATH.write_text(registry_src)
    print(f"\n  Generated ToolRegistry_fragment.java")

    # Generate report
    existing = find_existing_tools(EXISTING_TOOLS_DIR)
    report = generate_report(tools, existing)
    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
    REPORT_PATH.write_text(report)
    print(f"  Generated report.txt")

    # Print summary
    upstream_names = {t.name for t in tools}
    existing_names = set(existing.keys())
    missing = upstream_names - existing_names
    print(f"\n{'=' * 50}")
    print(f"  Upstream: {len(tools)} tools")
    print(f"  Existing: {len(existing)} tools")
    print(f"  Missing:  {len(missing)}: {', '.join(sorted(missing)) if missing else 'none'}")
    print(f"  Output:   {OUTPUT_DIR.relative_to(ROOT)}")
    print(f"{'=' * 50}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
