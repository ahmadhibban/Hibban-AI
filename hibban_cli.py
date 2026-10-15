#!/data/data/com.termux/files/usr/bin/python3
"""
hibban - Autonomous AI Coding Assistant for Termux
Powered by Google Gemini (Free Tier via Google AI Studio)
Supports Automatic Model Fallback & Multi-Key Rotation
"""

import os
import sys
import json
import time
import subprocess
import readline
import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

CONFIG_DIR = os.path.expanduser("~/.hibban")
if not os.path.exists(CONFIG_DIR) and os.path.exists(os.path.expanduser("~/.gcode")):
    CONFIG_DIR = os.path.expanduser("~/.gcode")
CONFIG_FILE = os.path.join(CONFIG_DIR, "config.json")
DEFAULT_MODEL = "gemini-3.7-flash"
FALLBACK_MODELS = [
    "gemini-3.7-flash",
    "gemini-flash-lite-latest",
    "gemini-3.8-flash",
    "gemini-2.5-flash"
]

# Terminal Colors
C_RESET = "\033[0m"
C_BOLD = "\033[1m"
C_CYAN = "\033[36m"
C_GREEN = "\033[32m"
C_YELLOW = "\033[33m"
C_RED = "\033[31m"
C_BLUE = "\033[34m"
C_MAGENTA = "\033[35m"

def get_http_session():
    session = requests.Session()
    retries = Retry(
        total=3,
        backoff_factor=1.5,
        status_forcelist=[500, 502, 503, 504],
        raise_on_status=False
    )
    adapter = HTTPAdapter(max_retries=retries)
    session.mount("https://", adapter)
    session.mount("http://", adapter)
    return session

HTTP_SESSION = get_http_session()

def get_config():
    keys = []
    env_key = os.environ.get("GEMINI_API_KEY")
    if env_key and env_key.strip():
        keys.append(env_key.strip())

    model = DEFAULT_MODEL

    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, "r") as f:
                cfg = json.load(f)
                if cfg.get("api_keys") and isinstance(cfg["api_keys"], list):
                    for k in cfg["api_keys"]:
                        if k and k.strip() and k.strip() not in keys:
                            keys.append(k.strip())
                elif cfg.get("api_key"):
                    k = cfg["api_key"].strip()
                    if k and k not in keys:
                        keys.append(k)

                if cfg.get("model"):
                    m = cfg["model"].strip()
                    if m not in ("gemini-2.0-flash", "gemini-1.5-flash", "gemini-2.5-pro"):
                        model = m
        except Exception:
            pass

    if not keys:
        print(f"\n{C_CYAN}{C_BOLD}╔════════════════════════════════════════════════════════════════╗{C_RESET}")
        print(f"{C_CYAN}{C_BOLD}║          🚀 Welcome to Hibban AI (Termux Autonomous Agent)     ║{C_RESET}")
        print(f"{C_CYAN}{C_BOLD}╚════════════════════════════════════════════════════════════════╝{C_RESET}\n")
        print(f"{C_YELLOW}Ekbar shudhu apnar Free Google Gemini API Key dite hobe:{C_RESET}")
        print(f"1. Browser-e ekhane jan: {C_BOLD}https://aistudio.google.com/app/apikey{C_RESET}")
        print(f"2. 'Create API key' te click kore key-ti copy korun (100% Free - 1500 req/day)")
        print(f"3. Ekhane paste kore Enter chapun:\n")

        try:
            user_key = input(f"{C_GREEN}{C_BOLD}Paste Gemini API Key: {C_RESET}").strip()
        except (KeyboardInterrupt, EOFError):
            print(f"\n{C_RED}Cancelled.{C_RESET}")
            sys.exit(1)

        if not user_key or user_key.lower() in ("exit", "quit", "q"):
            print(f"\n{C_RED}API Key chara Hibban AI cholbe na! Pore abar 'hibban' likhe try korun.{C_RESET}\n")
            sys.exit(1)

        keys.append(user_key)
        os.makedirs(CONFIG_DIR, exist_ok=True)
        with open(CONFIG_FILE, "w") as f:
            json.dump({"api_keys": keys, "model": DEFAULT_MODEL}, f, indent=2)
        print(f"\n{C_GREEN}✓ API Key shofolvabe save kora hoyeche ({CONFIG_FILE})!{C_RESET}\n")

    return keys, model

# Tools Definition for Gemini Tool Calling
TOOLS = [
    {
        "name": "run_command",
        "description": "Execute a shell/bash command in the current Termux working directory.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "command": {
                    "type": "STRING",
                    "description": "The command line string to execute."
                }
            },
            "required": ["command"]
        }
    },
    {
        "name": "read_file",
        "description": "Read file contents with line numbers.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "file_path": {
                    "type": "STRING",
                    "description": "Path to the file."
                },
                "start_line": {
                    "type": "INTEGER",
                    "description": "Starting line number (1-indexed)."
                },
                "end_line": {
                    "type": "INTEGER",
                    "description": "Ending line number (inclusive)."
                }
            },
            "required": ["file_path"]
        }
    },
    {
        "name": "write_file",
        "description": "Create a new file or completely overwrite an existing file.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "file_path": {
                    "type": "STRING",
                    "description": "Path to the file to create or overwrite."
                },
                "content": {
                    "type": "STRING",
                    "description": "The exact full content to write."
                }
            },
            "required": ["file_path", "content"]
        }
    },
    {
        "name": "edit_file",
        "description": "Replace an exact block of code/text in a file with new code/text.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "file_path": {
                    "type": "STRING",
                    "description": "Path to the file to edit."
                },
                "target_text": {
                    "type": "STRING",
                    "description": "Exact text substring to find and replace."
                },
                "replacement_text": {
                    "type": "STRING",
                    "description": "New replacement text."
                }
            },
            "required": ["file_path", "target_text", "replacement_text"]
        }
    },
    {
        "name": "list_directory",
        "description": "List files and subdirectories in a given directory.",
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "dir_path": {
                    "type": "STRING",
                    "description": "Directory path (defaults to current working directory)."
                }
            }
        }
    }
]

SYSTEM_PROMPT = """You are Hibban AI, an expert autonomous AI coding assistant running natively in Termux (Android Linux).
You have full access to tools to inspect, modify, create files, and run commands.
Always inspect files before modifying them.
Execute tasks autonomously. When you are done, explain your changes clearly in Banglish.
Current Working Directory: """ + os.getcwd()

def execute_tool(name, args):
    try:
        if name == "run_command":
            cmd = args.get("command", "")
            print(f"{C_YELLOW}⚡ [Command]{C_RESET} {cmd}")
            proc = subprocess.run(cmd, shell=True, capture_output=True, text=True, cwd=os.getcwd())
            out = proc.stdout
            err = proc.stderr
            res = ""
            if out: res += out
            if err: res += "\nSTDERR:\n" + err
            res += f"\n[Exit code: {proc.returncode}]"
            lines = res.strip().split("\n")
            preview = "\n".join(lines[:6])
            if len(lines) > 6:
                preview += f"\n... ({len(lines)-6} more lines)"
            print(f"{C_BLUE}{preview}{C_RESET}")
            return res[:25000]

        elif name == "read_file":
            path = args.get("file_path", "")
            print(f"{C_YELLOW}📖 [Read]{C_RESET} {path}")
            if not os.path.exists(path):
                return f"Error: File '{path}' does not exist."
            with open(path, "r", encoding="utf-8", errors="replace") as f:
                lines = f.readlines()
            start = args.get("start_line", 1) or 1
            end = args.get("end_line", len(lines)) or len(lines)
            start = max(1, start)
            end = min(len(lines), end)
            numbered = [f"{i+1}: {lines[i]}" for i in range(start-1, end)]
            return "".join(numbered)[:35000]

        elif name == "write_file":
            path = args.get("file_path", "")
            content = args.get("content", "")
            print(f"{C_YELLOW}📝 [Write]{C_RESET} {path}")
            os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
            with open(path, "w", encoding="utf-8") as f:
                f.write(content)
            return f"Successfully wrote {len(content)} bytes to '{path}'."

        elif name == "edit_file":
            path = args.get("file_path", "")
            target = args.get("target_text", "")
            replacement = args.get("replacement_text", "")
            print(f"{C_YELLOW}✏️  [Edit]{C_RESET} {path}")
            if not os.path.exists(path):
                return f"Error: File '{path}' does not exist."
            with open(path, "r", encoding="utf-8", errors="replace") as f:
                content = f.read()
            if target not in content:
                return f"Error: Target text not found in '{path}'. Make sure exact characters and indentation match."
            count = content.count(target)
            if count > 1:
                return f"Error: Target text matches {count} times in '{path}'. Provide a larger unique snippet."
            new_content = content.replace(target, replacement, 1)
            with open(path, "w", encoding="utf-8") as f:
                f.write(new_content)
            return f"Successfully updated '{path}'."

        elif name == "list_directory":
            path = args.get("dir_path", ".") or "."
            print(f"{C_YELLOW}📂 [List]{C_RESET} {path}")
            if not os.path.exists(path):
                return f"Error: Directory '{path}' does not exist."
            items = os.listdir(path)
            res = []
            for item in sorted(items)[:100]:
                full = os.path.join(path, item)
                res.append(f"{'[DIR] ' if os.path.isdir(full) else '[FILE]'} {item}")
            return "\n".join(res)

        return f"Error: Unknown tool '{name}'"
    except Exception as e:
        return f"Tool Execution Exception: {str(e)}"

def run_agent_loop(prompt, keys, model=DEFAULT_MODEL, history=None):
    if history is None:
        history = []

    history.append({
        "role": "user",
        "parts": [{"text": prompt}]
    })

    headers = {"Content-Type": "application/json"}
    active_model = model
    key_idx = 0

    max_steps = 30
    step = 0

    while step < max_steps:
        step += 1
        payload = {
            "system_instruction": {
                "parts": [{"text": SYSTEM_PROMPT}]
            },
            "contents": history,
            "tools": [{"function_declarations": TOOLS}]
        }

        candidate_models = [active_model] + [m for m in FALLBACK_MODELS if m != active_model]
        resp = None
        last_error_msg = ""

        # Loop through keys & models
        success = False
        for k_offset in range(len(keys)):
            cur_key = keys[(key_idx + k_offset) % len(keys)]

            for m in candidate_models:
                url = f"https://generativelanguage.googleapis.com/v1beta/models/{m}:generateContent?key={cur_key}"
                try:
                    r = HTTP_SESSION.post(url, headers=headers, json=payload, timeout=(15, 90))
                    if r.status_code == 200:
                        resp = r
                        success = True
                        key_idx = (key_idx + k_offset) % len(keys)
                        if m != active_model:
                            active_model = m
                            try:
                                with open(CONFIG_FILE, "r") as f: cfg = json.load(f)
                                cfg["model"] = m
                                with open(CONFIG_FILE, "w") as f: json.dump(cfg, f, indent=2)
                            except Exception:
                                pass
                        break
                    elif r.status_code in (404, 503, 429):
                        last_error_msg = f"{m} ({r.status_code}): {r.text[:120]}"
                        if r.status_code == 429:
                            if len(keys) > 1 and k_offset < len(keys) - 1:
                                break
                        time.sleep(0.5)
                        continue
                    else:
                        resp = r
                        last_error_msg = f"HTTP {r.status_code}: {r.text}"
                        break
                except requests.exceptions.ConnectionError as e:
                    last_error_msg = f"Connection failed (DNS or no internet): {e}"
                    time.sleep(0.5)
                    continue
                except requests.exceptions.Timeout as e:
                    last_error_msg = f"Timeout waiting for response: {e}"
                    time.sleep(0.5)
                    continue
                except Exception as e:
                    last_error_msg = f"Exception: {e}"
                    continue

            if success:
                break

        if not resp or resp.status_code != 200:
            print(f"\n{C_RED}{C_BOLD}Gemini API Error:{C_RESET}")
            if resp:
                print(f"{C_RED}[Code {resp.status_code}]: {resp.text}{C_RESET}\n")
            else:
                print(f"{C_RED}{last_error_msg}{C_RESET}")
                print(f"{C_YELLOW}💡 Tip: Protidin 1500 req free pawa jay. Ekadhik API key add korte ~/.hibban/config.json-e 'api_keys' list use korte paren.{C_RESET}\n")
            break

        data = resp.json()
        candidates = data.get("candidates", [])
        if not candidates:
            print(f"{C_RED}No response from model.{C_RESET}")
            break

        content = candidates[0].get("content", {})
        parts = content.get("parts", [])

        # Add assistant response to history
        history.append(content)

        tool_calls = [p["functionCall"] for p in parts if "functionCall" in p]
        text_parts = [p["text"] for p in parts if "text" in p]

        if text_parts:
            text_str = "".join(text_parts).strip()
            if text_str:
                print(f"\n{C_CYAN}{C_BOLD}hibban:{C_RESET}\n{text_str}\n")

        if not tool_calls:
            # Task finished
            break

        # Execute tool calls and send responses back
        response_parts = []
        for call in tool_calls:
            fname = call.get("name")
            fargs = call.get("args", {})
            result = execute_tool(fname, fargs)
            response_parts.append({
                "functionResponse": {
                    "name": fname,
                    "response": {"output": result}
                }
            })

        history.append({
            "role": "user",
            "parts": response_parts
        })

    return history

def main():
    keys, model = get_config()

    if len(sys.argv) > 1:
        prompt = " ".join(sys.argv[1:])
        print(f"\n{C_GREEN}{C_BOLD}Starting Task [{model}]:{C_RESET} {prompt}\n")
        run_agent_loop(prompt, keys, model=model)
        return

    # Interactive REPL mode
    print(f"\n{C_CYAN}{C_BOLD}Hibban AI - Termux Autonomous AI Coder [{model}]{C_RESET} (Type 'exit' to quit)\n")
    history = []
    while True:
        try:
            cwd_name = os.path.basename(os.getcwd()) or "/"
            print(f"\n{C_MAGENTA}{C_BOLD}╭─ hibban [{cwd_name}]{C_RESET}")
            prompt = input("\001\033[35m\033[1m\002╰─> \001\033[0m\002").strip()
            if not prompt:
                continue
            if prompt.lower() in ("exit", "quit", "q"):
                print(f"{C_YELLOW}Goodbye!{C_RESET}")
                break
            if prompt.lower() == "clear":
                history = []
                print(f"{C_GREEN}History cleared.{C_RESET}")
                continue
            history = run_agent_loop(prompt, keys, model=model, history=history)
        except (KeyboardInterrupt, EOFError):
            print(f"\n{C_YELLOW}Session ended.{C_RESET}")
            break

if __name__ == "__main__":
    main()
