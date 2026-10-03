"""Generate a mock election with VoteKit and write it as a BLT file this project can count.

usage: generate.py SCENARIO.json OUT.blt [--seed N] [--voters N]

A scenario describes voter blocs and candidate slates (parties) in VoteKit's terms:

    {
      "name": "two parties, 5 seats",
      "seats": 5,
      "voters": 10000,
      "model": "slate-pl",
      "slates": {"Red": ["R1", "R2", "R3", "R4"], "Blue": ["B1", "B2", "B3", "B4"]},
      "blocs": {"Red": 0.6, "Blue": 0.4},
      "cohesion": {"Red": {"Red": 0.85, "Blue": 0.15}, "Blue": {"Red": 0.2, "Blue": 0.8}},
      "preferences": {"Red": {"Red": {"R1": 0.4, "R2": 0.3, "R3": 0.2, "R4": 0.1}}},
      "truncation": {"mean_length": 4}
    }

- model: one of slate-pl, slate-bt, name-pl, name-bt, cambridge (VoteKit's bloc/slate models),
  ic (impartial culture, ignores blocs) or spatial (2-D Euclidean, ignores blocs).
- cohesion[bloc][slate]: how often that bloc's voters stick to that slate; each bloc's row sums to 1.
- preferences[bloc][slate][candidate]: support within a slate (each slate's values sum to 1).
  Anything left out is uniform across the slate's candidates.
- truncation (optional): every model except cambridge ranks every candidate on every ballot.
  Real voters rank fewer, so each voter's ballot is cut to a geometric length with this mean
  (at least 1). The cambridge model already reproduces real Cambridge, MA ballot lengths.
"""
import argparse
import json
import sys

import numpy as np
from votekit.ballot_generator import (
    BlocSlateConfig,
    cambridge_profile_generator,
    ic_profile_generator,
    name_bt_profile_generator,
    name_pl_profile_generator,
    slate_bt_profile_generator,
    slate_pl_profile_generator,
    spacial_profile_and_positions_generator,
)

BLOC_SLATE_MODELS = {
    "slate-pl": slate_pl_profile_generator,
    "slate-bt": slate_bt_profile_generator,
    "name-pl": name_pl_profile_generator,
    "name-bt": name_bt_profile_generator,
    "cambridge": cambridge_profile_generator,
}


def candidates_of(scenario):
    return [c for slate in scenario["slates"].values() for c in slate]


def bloc_slate_config(scenario, voters):
    slates = scenario["slates"]
    given = scenario.get("preferences", {})
    preferences = {}
    for bloc in scenario["blocs"]:
        preferences[bloc] = {}
        for slate, members in slates.items():
            support = given.get(bloc, {}).get(slate)
            preferences[bloc][slate] = support or {c: 1 / len(members) for c in members}
    return BlocSlateConfig(
        n_voters=voters,
        slate_to_candidates=slates,
        bloc_proportions=scenario["blocs"],
        preference_mapping=preferences,
        cohesion_mapping=scenario["cohesion"],
        silent=True,
    )


def generate(scenario, seed, voters):
    model = scenario.get("model", "slate-pl")
    candidates = candidates_of(scenario)
    if model in BLOC_SLATE_MODELS:
        return BLOC_SLATE_MODELS[model](bloc_slate_config(scenario, voters), rng_seed=seed)
    if model == "ic":
        return ic_profile_generator(candidates, voters, rng_seed=seed)
    if model == "spatial":
        profile, _, _ = spacial_profile_and_positions_generator(voters, candidates, rng_seed=seed)
        return profile
    sys.exit(f"unknown model '{model}'; expected one of {', '.join([*BLOC_SLATE_MODELS, 'ic', 'spatial'])}")


def ballots(profile, truncation, rng):
    """(weight, [candidate, ...]) per distinct ballot, truncated per voter if asked."""
    mean = truncation and truncation.get("mean_length")
    for ballot in profile.ballots:
        ranking = [next(iter(rank)) for rank in ballot.ranking]
        weight = int(round(float(ballot.weight)))
        if weight <= 0 or not ranking:
            continue
        if not mean:
            yield weight, ranking
            continue
        # Each of the `weight` voters gets their own geometric length (mean `mean`, at least 1),
        # capped at the full ranking; group them by length.
        p = 1 / mean
        k = np.arange(1, len(ranking) + 1)
        probabilities = p * (1 - p) ** (k - 1)
        probabilities[-1] += 1 - probabilities.sum()  # everything longer is the full ranking
        for length, count in zip(k, rng.multinomial(weight, probabilities)):
            if count:
                yield int(count), ranking[:length]


def write_blt(path, scenario, profile, seed):
    candidates = candidates_of(scenario)
    ids = {c: i + 1 for i, c in enumerate(candidates)}
    rng = np.random.default_rng(seed)
    # Truncation turns different full ballots into the same short one, so merge them.
    merged = {}
    for weight, ranking in ballots(profile, scenario.get("truncation"), rng):
        key = tuple(ids[c] for c in ranking)
        merged[key] = merged.get(key, 0) + weight
    with open(path, "w") as out:
        out.write(f"{len(candidates)} {scenario['seats']}\n")
        for key, weight in merged.items():
            out.write(f"{weight} {' '.join(map(str, key))} 0\n")
        out.write("0\n")
        for c in candidates:
            out.write(f'"{c}"\n')
        out.write(f'"{scenario.get("name", "mock election")} (seed {seed})"\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("scenario")
    parser.add_argument("out")
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--voters", type=int, help="override the scenario's voter count")
    args = parser.parse_args()
    with open(args.scenario) as f:
        scenario = json.load(f)
    voters = args.voters or scenario["voters"]
    write_blt(args.out, scenario, generate(scenario, args.seed, voters), args.seed)


if __name__ == "__main__":
    main()
