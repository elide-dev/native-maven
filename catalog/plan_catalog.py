#!/usr/bin/env python3
"""
Plan the nmvn prebuilt-binary catalog: pick which N native images to build so that a generated
Spring Boot project downloads as little as possible while still getting all its plugins baked.

The problem
-----------
Coverage does NOT need multiple binaries: for language=java there is no groupId:artifactId collision
across the whole initializr space, so one image can bake everything (see build-nmvn-for-initializr.sh).
Multiple binaries are purely a SIZE optimization -- the union image carries ~167 MB of plugin closure
that ~85% of projects never load.

Formulation
-----------
Each project needs the core realms plus some subset d of the six features in model.json ("demand
class"). A binary is itself a subset B of features, and it SERVES a project iff B is a superset of d
-- anything less and some plugin falls back to dynamic resolution. Cost to a user is the image they
must download, so:

    minimize   E_d [ min { size(B) : B in catalog, B superset of d } ]
    over       catalogs of k binaries

Because every demand class has nonzero probability under the independence model, one binary must be
the full set F or some class is unservable; the optimizer therefore always pins F into the catalog and
chooses the other k-1 freely. That pinned entry is the correctness fallback, and the specialized ones
only ever make things cheaper.

This is exact, not greedy, for k <= 5: there are only 2^6 = 64 candidate binaries, so the search is
C(63, k-1) which is at most ~600k. Larger k falls back to forward-greedy, which is fine here because
the objective is monotone and near-submodular (adding a binary can only lower the min).

Calibration warning
-------------------
imageMbPerClosureMb (2.0) is the one guessed number: baked realms are heap-snapshotted plus reflection
metadata, so image growth exceeds jar bytes, but the true multiplier has not been measured. Build any
two adjacent tiers and set it to (size_b - size_a) / (closure_b - closure_a). The RANKING of features
is insensitive to it (it scales all deltas equally); the absolute savings claims are not.

Usage:
    ./plan_catalog.py                  # plan for k=1..6, print comparison
    ./plan_catalog.py --k 4 --emit catalog.json
    ./plan_catalog.py --sensitivity    # is the chosen partition stable? (measured: NO, 2/200)
    ./plan_catalog.py --regret         # does that instability COST anything? (measured: ~5MB, no)

Measured 2026-07-29: --sensitivity says the partition is unstable under 4x prior uncertainty (stable
in 2/200 draws), but --regret shows the optimum is FLAT -- planning on the estimated priors costs a
mean 4.9MB (p90 14.2MB) versus knowing the true ones, against a 262MB mean saving over one universal
image, and the catalog wins in 150/150 sampled worlds. So the unstable argmin is cosmetic: do not
wait for telemetry to build the catalog, and do not trust the exact feature grouping either.
"""

import argparse
import itertools
import json
import os
import random
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def load_model(path):
    with open(path) as fh:
        return json.load(fh)


class Space:
    """The demand distribution and the size of every candidate binary."""

    def __init__(self, model):
        self.model = model
        self.names = sorted(model["features"])
        self.n = len(self.names)
        self.full = (1 << self.n) - 1

        feats = model["features"]
        self.priors = [feats[f]["prior"] for f in self.names]
        mult = model["imageMbPerClosureMb"]
        self.delta_mb = [feats[f]["closureMb"] * mult for f in self.names]
        self.base_mb = model["baseImageMb"]

        # Demand classes: every subset, probability from independent per-feature priors. Independence
        # is an approximation -- restdocs and sbom plausibly correlate (both "process maturity"
        # choices) -- but the tail classes it invents are exactly the ones the pinned full binary
        # absorbs, so the catalog shape is robust to it. --sensitivity checks that claim.
        self.demand = []
        for mask in range(1 << self.n):
            p = 1.0
            for i in range(self.n):
                p *= self.priors[i] if mask & (1 << i) else (1.0 - self.priors[i])
            if p > 0:
                self.demand.append((mask, p))

    def size(self, mask):
        return self.base_mb + sum(self.delta_mb[i] for i in range(self.n) if mask & (1 << i))

    def label(self, mask):
        if mask == 0:
            return "core"
        if mask == self.full:
            return "full"
        return "+".join(self.names[i] for i in range(self.n) if mask & (1 << i))

    def expected_mb(self, catalog):
        """E[download size], where each demand class takes the smallest binary that covers it."""
        total = 0.0
        for d, p in self.demand:
            best = min(self.size(b) for b in catalog if (b & d) == d)
            total += p * best
        return total

    def distribution(self, catalog):
        """Per-binary share of traffic and the size each of those users pays."""
        share = {b: 0.0 for b in catalog}
        for d, p in self.demand:
            b = min((b for b in catalog if (b & d) == d), key=lambda b: (self.size(b), b))
            share[b] += p
        return share


def plan(space, k):
    """Best catalog of exactly k binaries. The full set is pinned so every project is servable."""
    others = [m for m in range(1 << space.n) if m != space.full]
    if k == 1:
        return [space.full]

    if k - 1 <= 4:
        best, best_cost = None, float("inf")
        for combo in itertools.combinations(others, k - 1):
            cat = list(combo) + [space.full]
            c = space.expected_mb(cat)
            if c < best_cost:
                best, best_cost = cat, c
        return sorted(best)

    cat = [space.full]
    for _ in range(k - 1):
        pick = min((m for m in others if m not in cat),
                   key=lambda m: space.expected_mb(cat + [m]))
        cat.append(pick)
    return sorted(cat)


def print_plan(space, cat):
    share = space.distribution(cat)
    print(f"    {'binary':<44} {'size':>8}  {'traffic':>8}")
    for b in sorted(cat, key=lambda b: space.size(b)):
        print(f"    {space.label(b):<44} {space.size(b):>6.0f}MB  {share[b] * 100:>7.1f}%")


def perturb(model, rng, spread=4.0):
    """Redraw every prior within a multiplicative band -- a stand-in for 'we have no telemetry'."""
    out = json.loads(json.dumps(model))
    for f in out["features"]:
        p = out["features"][f]["prior"]
        out["features"][f]["prior"] = min(0.9, max(0.001, p * rng.uniform(1.0 / spread, spread)))
    return out


def regret_report(model, k, trials, spread):
    """Does planning on WRONG priors actually cost anything?

    --sensitivity showed the chosen feature partition is unstable under prior uncertainty, but an
    unstable argmin is only a problem if the alternatives are meaningfully worse. This measures that
    directly: plan once on the estimated priors (the catalog we would really build), then repeatedly
    invent a 'true' world, and compare our fixed catalog against the catalog we would have built had
    we known that world.

        regret = cost(fixed catalog, true world) - cost(optimal catalog, true world)

    Small regret means the instability is cosmetic and the catalog is safe to build now. Large regret
    means the partition genuinely needs real usage data first. The k=1 column is the honest baseline:
    if a mis-planned catalog still beats one universal image, splitting is justified regardless.
    """
    rng = random.Random(11)
    fixed = plan(Space(model), k)

    regrets, fixed_costs, opt_costs, uni_costs = [], [], [], []
    for _ in range(trials):
        world = Space(perturb(model, rng, spread))
        best = plan(world, k)
        c_fixed = world.expected_mb(fixed)
        c_opt = world.expected_mb(best)
        regrets.append(c_fixed - c_opt)
        fixed_costs.append(c_fixed)
        opt_costs.append(c_opt)
        uni_costs.append(world.expected_mb([world.full]))

    regrets.sort()
    mean = sum(regrets) / len(regrets)
    p90 = regrets[int(0.9 * len(regrets))]
    print(f"k={k}, {trials} draws, priors perturbed up to {spread:g}x either way\n")
    print(f"  regret of planning on estimated priors : mean {mean:5.1f}MB   p90 {p90:5.1f}MB   "
          f"max {regrets[-1]:5.1f}MB")
    print(f"  E[download] with our fixed catalog     : mean {sum(fixed_costs) / trials:6.0f}MB")
    print(f"  E[download] with per-world optimum     : mean {sum(opt_costs) / trials:6.0f}MB")
    print(f"  E[download] with one universal image   : mean {sum(uni_costs) / trials:6.0f}MB")
    beats = sum(1 for f, u in zip(fixed_costs, uni_costs) if f < u)
    print(f"\n  fixed catalog beats the universal image in {beats}/{trials} worlds "
          f"(mean saving {sum(u - f for f, u in zip(fixed_costs, uni_costs)) / trials:.0f}MB)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=os.path.join(HERE, "model.json"))
    ap.add_argument("--k", type=int)
    ap.add_argument("--emit")
    ap.add_argument("--sensitivity", action="store_true")
    ap.add_argument("--regret", action="store_true",
                    help="cost of planning on wrong priors (the question --sensitivity raises)")
    ap.add_argument("--trials", type=int, default=200)
    ap.add_argument("--spread", type=float, default=4.0)
    args = ap.parse_args()

    model = load_model(args.model)
    space = Space(model)

    if args.regret:
        regret_report(model, args.k or 3, args.trials, args.spread)
        return

    if args.sensitivity:
        # The priors are estimates. Redraw each one from a wide lognormal-ish band and see how often
        # the k=4 plan's feature partition survives. If it does, the plan is safe to build before
        # real telemetry exists.
        random.seed(7)
        baseline = tuple(sorted(plan(space, 4)))
        agree = 0
        trials = 200
        for _ in range(trials):
            perturbed = json.loads(json.dumps(model))
            for f in perturbed["features"]:
                p = perturbed["features"][f]["prior"]
                perturbed["features"][f]["prior"] = min(0.9, max(0.001, p * random.uniform(0.25, 4.0)))
            if tuple(sorted(plan(Space(perturbed), 4))) == baseline:
                agree += 1
        print(f"k=4 plan is stable in {agree}/{trials} draws with priors perturbed 4x either way")
        print("baseline plan:")
        print_plan(space, list(baseline))
        return

    if args.k:
        cat = plan(space, args.k)
        print(f"catalog for k={args.k}: E[download] = {space.expected_mb(cat):.0f}MB")
        print_plan(space, cat)
        if args.emit:
            emit(space, cat, args.emit)
            print(f"\nwrote {args.emit}")
        return

    print(f"base image {space.base_mb:.0f}MB, full image {space.size(space.full):.0f}MB "
          f"(+{space.size(space.full) - space.base_mb:.0f}MB of feature realms)\n")
    prev = None
    for k in range(1, space.n + 2):
        cat = plan(space, k)
        e = space.expected_mb(cat)
        delta = "" if prev is None else f"   (-{prev - e:.0f}MB vs k={k - 1})"
        print(f"k={k}: E[download] = {e:>6.0f}MB{delta}")
        print_plan(space, cat)
        print()
        prev = e


def emit(space, cat, path):
    """Write the catalog the selector reads. Plugin lists are what build-nmvn-prebuilt.sh consumes."""
    feats = space.model["features"]
    out = {
        "bootVersion": space.model["bootVersion"],
        "language": space.model["language"],
        "corePlugins": space.model["core"],
        "unbakeable": space.model["unbakeable"]["plugins"],
        "featurePlugins": {f: feats[f]["plugins"] for f in space.names},
        "binaries": [],
    }
    for b in sorted(cat, key=lambda b: space.size(b)):
        names = [space.names[i] for i in range(space.n) if b & (1 << i)]
        # "+" separates features in the human-readable label but is awkward in a filename, and these
        # names are used verbatim as output binaries.
        slug = space.label(b).replace("+", "-")
        out["binaries"].append({
            "name": f"nmvn-java-{space.model['bootVersion']}-{slug}",
            "features": names,
            "estimatedMb": round(space.size(b)),
            "plugins": space.model["core"] + [p for f in names for p in feats[f]["plugins"]],
        })
    with open(path, "w") as fh:
        json.dump(out, fh, indent=2)
        fh.write("\n")


if __name__ == "__main__":
    sys.exit(main())
