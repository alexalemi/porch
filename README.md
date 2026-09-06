# atilde

This is like the AT protocol but for a tilde server. `atilde` is the idea;
`porch` is the tool.

Having all of the users on the same machine simplifies a lot: we can really
embrace the idea that everything is a file by making everything a file, which
is the core idea of the AT Protocol as explained in this
[blog post](https://overreacted.io/a-social-filesystem/).

Every user has their own `~/.porch/` which contains files they author.

    ~/.porch/
        posts/<tid>.md       # short posts and replies
        blog/<tid>.md        # long posts with a title
        links/<tid>.md       # link recommendations
        likes/<tid>          # one subject each
        reactions/<tid>      # one subject and one emoji each
        feeds/<tid>          # RSS feeds you recommend
        profile              # name, links, bio  (planned, see below)

Text collections (`posts`, `blog`, `links`) carry a markdown body and get a
`.md` extension. Reference collections (`likes`, `reactions`, `feeds`) are
front matter only and are stored bare.

Addresses are `user/collection/rkey`, with no extension; the tool resolves the
extension from the collection. A leading `@` is tolerated, so
`porch like @sam/posts/3ab…` reads naturally.

Deletion is `rm`, and unlike is `rm` too. There may be dangling references
everywhere; every reader treats a missing file as absence, not an error.

## Using it

```
porch tui                                 browse, post, reply, like, react — interactively
porch timeline [--limit N] [--user U] [--coll C]
                                          everyone's porch, newest first
porch post <text…>                        a short post; --reply <addr> to reply
porch blog --title T [text…]              a long post; body from argv or stdin
porch link <url> [--title T] [why…]       recommend a link
porch like <addr>                         like any address
porch react <addr> <emoji>                react to any address
porch feed <url> [--title T]              publish a feed you read
porch ls [user] [coll]                    addresses, oldest first
porch cat <addr>                          show one document
porch users                               everyone on this box with a porch
porch tid                                 print a fresh TID
```

Commands that create something print the new address. `post` and `blog` take
the body from the remaining arguments, or from stdin when there are none, so
`porch blog --title T < draft.md` works. Liking or reacting with the same emoji
twice is one statement and reuses the existing file rather than stacking
duplicates. Option values must be given with long flags (`--limit 5`, not
`-n 5`): short flags are booleans and never consume a value.

### Environment

- `PORCH_USER` — who you are. Defaults to `$USER`.
- `PORCH_HOMES` — where home directories live. Defaults to `/home`, with
  `$HOME` honoured for the current user. Point it at any directory and you
  get a throwaway box: several users, their own porches, no root and no real
  accounts. That is how the tests stay off your real porch, and how you can
  try a multi-user timeline before anyone else is on the machine.

## Running and building

`porch` is written in [cljc](https://github.com/alexalemi/cljc). Development
needs no build: `deps.edn` puts `src/` on cljc's load path, so `bin/porch …`
and `make test` run straight from source.

- `make` bundles the sources and interpreter into a single `porch` binary
  (~410K, links only libc and libm); `make porch-static` builds a static one
  for a box you didn't build on.
- `make install` puts it in `$PREFIX/bin` (default `~/.local/bin`).
- `make check` exercises the real commands against a throwaway `$HOME`.

The TUI needs cljc's `cljc/raw-mode*`, `cljc/read-key*` and `cljc/term-size*`
natives, and deliberately not ncurses: that would only be reachable through
the FFI, which compiles and `dlopen`s a shared object from `/tmp` at runtime, a
real hazard on a multi-user box. The same reasoning keeps the store on cljc's
`list-dir*`, `dir?*` and `env*` natives instead of the FFI-backed `fs.clj`.

It also runs unmodified on [Babashka](https://babashka.org) and
[jolt](https://github.com/alexalemi/jolt): `bin/porch-bb` and `bin/porch-jolt`
load `bb/cljc.clj`, a namespace literally named `cljc` that reimplements the
handful of natives (env, dir listing, clock, pid, raw terminal via `stty`).
jolt additionally needs the pure-Clojure `clj-yaml` and `babashka.cli` copies
in `jolt/`, reached through the `:jolt` alias in deps.edn. `make test-bb` and
`make test-jolt` run the same test file there; `make porch-jolt` builds a jolt
binary. cljc stays the primary target and is what `make` bundles.

## TIDs

A `<tid>` is a TID: a 13-character string encoding microseconds since the
epoch plus a small clock ID, in a base32 alphabet chosen so that alphabetical
order is chronological. This way `ls` gives you the timeline for free and the
TID is the creation time, so no separate `createdAt` field is needed. Merging
everyone's timelines is a plain sort on filenames with no documents opened.
`porch tid` prints a new one.

## File format

The file format is markdown with a YAML front matter between `---` lines. The
fence is the only way to carry metadata: a file with no `---` fence is all
body, and we never sniff a bare file to see whether it happens to parse as
YAML. That costs `likes/` and `feeds/` a fence they would otherwise not need,
and buys a rule with no ambiguous cases. A post reading
`Reading: notes on Simmel` is a post, not a `Reading:` field, so parse and
render round-trip exactly. A fence that never closes, or one whose contents
aren't a YAML map, is a post that opens with a horizontal rule; nothing is
ever silently dropped.

## Schemas

- `posts/` — the body is the post; top-level posts have no front matter at
  all. A reply has `reply: {root: <addr>, parent: <addr>}`, where root is the
  parent's root if it has one, otherwise the parent itself.
- `blog/` — `title:` required; the body is the article. Separate from posts
  so it can be collapsed.
- `links/` — `url:` required, `title:` optional; the body is why you're
  recommending it.
- `likes/` — `subject: <addr>` (fenced, no body). Any collection can be
  liked, including someone's link or feed.
- `reactions/` — `subject: <addr>`, `emoji: 🔥` (fenced, no body). Several
  different emoji per subject are fine; the same one twice is one file.
- `feeds/` — `url:` plus optional `title:` (fenced, no body). This publishes
  "I read this feed".

### Planned, not yet implemented

- `profile` — `name:`, optional `links:`, body is the bio.
- Mentions (`@sam`) and tags (`#cnc`) parsed from post bodies, not declared.
- An optional `edited:` timestamp on posts.
- `porch link` fetching a missing title.
- A shared feed fetcher caching entries under `/var/cache/porch/`, never
  inside anyone's `.porch`, so it needs no write access to home directories.
