"use strict";

// The options a method may take, as the server names them (GET /api/methods says which each takes).
// A blank field isn't sent, so the ruleset's value or the method's default applies.
const OPTIONS = {
  seats: {
    label: "Seats", type: "number", min: 1,
    hint: "Blank: the BLT file's own count. A CSV file has none, so give it.",
  },
  ruleset: {
    label: "Ruleset", type: "ruleset",
    hint: "A bundled jurisdiction's rules. Its values show greyed in the fields below; what you type overrides them.",
  },
  quota: {
    label: "Quota", type: "text",
    suggestions: ["droop", "hare", "hagenbach-bischoff", "imperiali", "droop-rounded:2"],
    hint: "droop, hare, hagenbach-bischoff, imperiali or droop-rounded:N. Blank: Droop.",
  },
  transfer: {
    label: "Transfer", type: "text",
    suggestions: ["fractional", "wigm", "last-parcel", "last-parcel-deferred-rounding", "unweighted-inclusive",
      "unweighted-exclusive:6", "weighted-transferable", "weighted-inclusive", "fractional-truncated"],
    hint: "How surpluses move. Blank: fractional (Gregory).",
  },
  rounding: {
    label: "Rounding", type: "text", suggestions: ["exact", "truncate:9", "truncate:5", "truncate:2"],
    hint: "exact or truncate:N decimal places. Blank: exact.",
  },
  tiebreak: {
    label: "Tie-break", type: "text",
    suggestions: ["declared", "previous-round,declared", "first-preference,declared", "seeded-random:1"],
    hint: "A comma-separated chain of declared, previous-round, first-preference, seeded-random:SEED.",
  },
  tiedRankings: {
    label: "Tied rankings", type: "select", choices: ["reject", "split", "truncate", "skip"],
    hint: "What a ballot ranking candidates equally does. Blank: split where the method can, else reject.",
  },
  minimumPreferences: {
    label: "Minimum preferences", type: "number", min: 0,
    hint: "A ballot ranking fewer candidates than this is informal.",
  },
  precision: {
    label: "Precision", type: "number", min: 1,
    hint: "Decimal places the count works to. Blank: 9, as droop's.",
  },
  bordaScoring: {
    label: "Scoring", type: "select", choices: ["classic", "dowdall"],
    hint: "classic: n−1 points down to 0. dowdall: 1, 1/2, 1/3, … (Nauru).",
  },
  givenList: {
    label: "Given list", type: "text", list: true,
    hint: "Candidates, comma-separated, who take the first places in this order. Each place still shows the "
      + "method's own potential winners, so a list chosen otherwise can be checked. Blank: the method decides.",
  },
};

// The rules a ruleset sets that have no field here, in README's words. Only classic STV reads them.
const FIXED_RULES = {
  batchExclusion: (on) => (on
    ? "Batch exclusion: the lowest candidates are excluded together when together they still couldn't overtake "
      + "the next."
    : "One candidate is excluded at a time."),
  deferSurpluses: (on) => on && "Surplus deferral: pending surpluses wait while exclusions go ahead, unless they "
    + "could change who is excluded.",
  votesRequired: (on) => on && "Votes required: a candidate is also elected on reaching the active vote divided by "
    + "one more than the seats left.",
  surplusOrder: (order) => (order === "election" ? "Surpluses move in order of election."
    : "Surpluses move largest first."),
  electAsTheyStand: (on) => on && "Elect as they stand: when an exclusion leaves as many candidates as seats, "
    + "they're elected without its papers moving, and the last seat between two goes to the one ahead.",
  electSureWinners: (on) => on && "Sure winners: the leaders for the seats left are elected as soon as the others "
    + "together can't overtake the last of them.",
  exclusionOrder: (order) => ({
    value: "An excluded candidate's ballots move by transfer value, highest first.",
    "first-preferences-then-value": "An excluded candidate's first preferences move first, then the rest by "
      + "transfer value.",
    parcel: "An excluded candidate's ballots move parcel by parcel, in the order received.",
  }[order] || `Exclusion order: ${order}.`),
  simultaneousSurpluses: (on) => on && "Everyone elected in a round has their surplus moved together.",
  mathematicalElimination: (on) => on && "Mathematical elimination: every candidate who can no longer win is "
    + "defeated together before a surplus moves.",
  rankingsElimination: (on) => on && "So is anyone ranked on too few ballots still in play to reach the last seat.",
  quotaEachRound: (on) => on && "The quota is recomputed before every round.",
};

const $ = (id) => document.getElementById(id);
let methods = [];
let rulesets = [];
let examples = [];
let maxRequestBytes = Infinity;
let lastRequest = null;

async function start() {
  try {
    const [api, list] = await Promise.all([
      fetch("/api/methods").then((r) => r.json()),
      fetch("/examples/examples.json").then((r) => r.json()),
    ]);
    methods = api.methods;
    examples = list;
    maxRequestBytes = api.limits.maxRequestBytes;
    buildMethods();
    rulesets = api.rulesets;
    buildOptions();
    buildExamples();
    $("limits").textContent = `This demo takes ballot files up to ${Math.round(maxRequestBytes / 1024 / 1024)} MB, `
      + `counts ${api.limits.maxConcurrentCounts} elections at once and waits ${api.limits.countTimeoutSeconds} s `
      + "for each; larger counts belong to the command line.";
  } catch (e) {
    showError(`The page couldn't reach the server: ${e.message}`);
  }
  $("method").addEventListener("change", showMethodOptions);
  $("example").addEventListener("change", loadExample);
  $("file").addEventListener("change", openFile);
  $("count-form").addEventListener("submit", (event) => {
    event.preventDefault();
    count();
  });
  for (const button of $("downloads").querySelectorAll("button")) {
    button.addEventListener("click", () => download(button.dataset.format));
  }
}

function buildMethods() {
  const select = $("method");
  for (const method of methods) {
    select.append(new Option(method.name, method.name));
  }
}

function buildOptions() {
  const container = $("options");
  const row = document.createElement("div");
  row.className = "row";
  for (const [name, option] of Object.entries(OPTIONS)) {
    const field = document.createElement("div");
    field.className = "field";
    field.dataset.option = name;
    const label = document.createElement("label");
    label.htmlFor = `option-${name}`;
    label.textContent = option.label;
    field.append(label);
    let input;
    if (option.type === "ruleset" || option.type === "select") {
      input = document.createElement("select");
      input.append(new Option(option.type === "ruleset" ? "None" : "Default", ""));
      const choices = option.type === "ruleset" ? rulesets.map((r) => r.name) : option.choices;
      for (const choice of choices) {
        input.append(new Option(choice, choice));
      }
    } else {
      input = document.createElement("input");
      input.type = option.type;
      if (option.min !== undefined) {
        input.min = option.min;
        input.step = 1;
      }
      if (option.suggestions) {
        const list = document.createElement("datalist");
        list.id = `suggest-${name}`;
        for (const value of option.suggestions) {
          list.append(new Option(value));
        }
        field.append(list);
        input.setAttribute("list", list.id);
      }
      input.autocomplete = "off";
    }
    input.id = `option-${name}`;
    input.name = name;
    const hint = document.createElement("p");
    hint.className = "hint";
    hint.id = `hint-${name}`;
    hint.textContent = option.hint;
    input.setAttribute("aria-describedby", hint.id);
    field.append(input, hint);
    if (option.type === "ruleset") {
      const about = document.createElement("p");
      about.className = "hint";
      about.id = "ruleset-about";
      const rules = document.createElement("ul");
      rules.className = "ruleset-rules";
      rules.id = "ruleset-rules";
      input.addEventListener("change", applyRuleset);
      field.append(about, rules);
      container.append(field);
    } else {
      row.append(field);
    }
  }
  container.append(row);
  showMethodOptions();
}

function showMethodOptions() {
  const method = methods.find((m) => m.name === $("method").value);
  $("method-about").textContent = method ? method.description : "";
  for (const field of $("options").querySelectorAll("[data-option]")) {
    field.hidden = !method || !method.options.includes(field.dataset.option);
  }
  applyRuleset();
}

/**
 * Shows what the chosen ruleset sets: its value in each field it gives one for (greyed, so a blank
 * field visibly means the ruleset's value), and, for classic STV, the rules it sets that have no field.
 */
function applyRuleset() {
  const select = $("option-ruleset");
  const ruleset = select ? rulesets.find((r) => r.name === select.value) : undefined;
  const settings = ruleset ? ruleset.settings : {};
  $("ruleset-about").textContent = ruleset ? ruleset.description : "";
  for (const name of Object.keys(OPTIONS)) {
    const input = $(`option-${name}`);
    if (name === "ruleset" || !input) {
      continue;
    }
    const value = settings[name];
    if (input.tagName === "SELECT") {
      input.options[0].text = value === undefined ? "Default" : `Ruleset: ${value}`;
    } else {
      input.placeholder = value === undefined ? "" : String(value);
    }
  }
  const rules = $("ruleset-rules");
  rules.replaceChildren();
  if ($("method").value === "stv") {
    for (const [name, value] of Object.entries(settings)) {
      const text = FIXED_RULES[name] ? FIXED_RULES[name](value) : null;
      if (text) {
        const item = document.createElement("li");
        item.textContent = text;
        rules.append(item);
      }
    }
  }
  rules.hidden = rules.childElementCount === 0;
}

function buildExamples() {
  const select = $("example");
  const groups = new Map();
  examples.forEach((example, i) => {
    const name = example.group || "Examples";
    if (!groups.has(name)) {
      const group = document.createElement("optgroup");
      group.label = name;
      groups.set(name, group);
      select.append(group);
    }
    groups.get(name).append(new Option(example.title, String(i)));
  });
}

async function loadExample() {
  const example = examples[Number($("example").value)];
  $("example-about").textContent = example ? example.about : "";
  if (!example) {
    return;
  }
  const response = await fetch(`/examples/${example.file}`);
  $("ballots").value = await response.text();
  $("format").value = "";
  for (const input of $("options").querySelectorAll("input, select")) {
    input.value = "";
    input.dispatchEvent(new Event("change"));
  }
  $("method").value = example.options.method || "stv";
  for (const [name, value] of Object.entries(example.options)) {
    const input = $(`option-${name}`);
    if (input) {
      input.value = value;
      input.dispatchEvent(new Event("change"));
    }
  }
  showMethodOptions();
  count();
}

function openFile() {
  const file = $("file").files[0];
  if (!file) {
    return;
  }
  if (file.size > maxRequestBytes) {
    showError(`${file.name} is larger than this demo takes; count it with the command line instead.`);
    return;
  }
  file.text().then((text) => {
    $("ballots").value = text;
    $("example").value = "";
    $("example-about").textContent = "";
    $("format").value = file.name.toLowerCase().endsWith(".csv") ? "csv"
      : file.name.toLowerCase().endsWith(".blt") ? "blt" : "";
  });
}

/** The request the form describes: blank and hidden fields left out. */
function request(reportFormat) {
  const method = $("method").value;
  const body = { ballots: $("ballots").value, method, reportFormat };
  if ($("format").value) {
    body.format = $("format").value;
  }
  const taken = methods.find((m) => m.name === method)?.options || [];
  for (const name of taken) {
    const value = $(`option-${name}`).value.trim();
    if (value !== "") {
      body[name] = OPTIONS[name].type === "number" ? Number(value)
        : OPTIONS[name].list ? value.split(",").map((item) => item.trim()).filter((item) => item !== "")
          : value;
    }
  }
  return body;
}

async function post(body) {
  const response = await fetch("/api/count", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  const text = await response.text();
  if (!response.ok) {
    let message = text;
    try {
      message = JSON.parse(text).error;
    } catch (e) {
      // not JSON: show the text as it came
    }
    throw Object.assign(new Error(message), { status: response.status });
  }
  return { text, warning: response.headers.get("X-Count-Warning") };
}

async function count() {
  clearMessages();
  if ($("ballots").value.trim() === "") {
    showError("Paste a ballot file, open one or choose an example first.", "ballots");
    return;
  }
  const body = request("html");
  const button = $("count");
  button.disabled = true;
  $("status").textContent = "Counting…";
  try {
    const { text, warning } = await post(body);
    lastRequest = body;
    $("report").srcdoc = text;
    $("report").hidden = false;
    $("placeholder").hidden = true;
    $("downloads").hidden = false;
    if (warning) {
      $("warning").textContent = `Warning: ${warning}`;
      $("warning").hidden = false;
    }
    $("status").textContent = "Counted.";
  } catch (e) {
    // A refused count leaves no report: the last one would read as this one's.
    lastRequest = null;
    $("report").hidden = true;
    $("report").srcdoc = "";
    $("downloads").hidden = true;
    $("placeholder").hidden = false;
    showError(e.message, fieldOf(e.message));
    $("status").textContent = "Not counted.";
  } finally {
    button.disabled = false;
  }
}

async function download(format) {
  if (!lastRequest) {
    return;
  }
  try {
    const { text } = await post({ ...lastRequest, reportFormat: format });
    const type = { text: "text/plain", csv: "text/csv", json: "application/json", html: "text/html" }[format];
    const link = document.createElement("a");
    link.href = URL.createObjectURL(new Blob([text], { type }));
    link.download = `count.${format === "text" ? "txt" : format}`;
    link.click();
    URL.revokeObjectURL(link.href);
  } catch (e) {
    showError(e.message);
  }
}

// The library's own wording for an option's value it can't read.
const VALUE_ERRORS = [
  [/^Unknown quota\b/, "quota"],
  [/^(Unknown transfer method|Transfer method)\b/, "transfer"],
  [/^Unknown rounding rule\b/, "rounding"],
  [/^(Unknown tie-break rule|Invalid seed)\b/, "tiebreak"],
  [/^Unknown tied-ranking policy\b/, "tiedRankings"],
  [/^Unknown bordaScoring\b/, "bordaScoring"],
];

/** The form field an error is about ("seats doesn't apply…", "Unknown quota…"), if any. */
function fieldOf(message) {
  const text = message || "";
  const word = text.split(/[^A-Za-z]/)[0];
  if (word === "ballots" || word === "method" || word === "format") {
    return word;
  }
  if (Object.hasOwn(OPTIONS, word)) {
    return `option-${word}`;
  }
  const known = VALUE_ERRORS.find(([pattern]) => pattern.test(text));
  return known ? `option-${known[1]}` : null;
}

function showError(message, fieldId) {
  $("error").textContent = message;
  $("error").hidden = false;
  const field = fieldId ? $(fieldId) : null;
  if (field) {
    // Said again under the field, which focusing scrolls to, out of sight of the report panel.
    const inline = document.createElement("p");
    inline.className = "field-error";
    inline.id = `error-${fieldId}`;
    inline.textContent = message;
    field.insertAdjacentElement("afterend", inline);
    field.setAttribute("aria-invalid", "true");
    field.setAttribute("aria-errormessage", inline.id);
    field.focus();
  }
}

function clearMessages() {
  for (const id of ["error", "warning"]) {
    $(id).hidden = true;
    $(id).textContent = "";
  }
  for (const invalid of document.querySelectorAll("[aria-invalid]")) {
    invalid.removeAttribute("aria-invalid");
    invalid.removeAttribute("aria-errormessage");
  }
  for (const inline of document.querySelectorAll(".field-error")) {
    inline.remove();
  }
}

start();
