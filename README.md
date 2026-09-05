# atilde

This is like the AT protocol but for a tilde server.

Having all of the users on the same machine simplifies a lot, we can really 
embrace the idea that everything is a file by making everything a file, which
is the core idea of the AT Protocol as explained in this [blog post](https://overreacted.io/a-social-filesystem/).

Every user has their own `~/.porch/` which contains files they author.

~/.porch/
	profile						# name, links, bio
	posts/<tid>.md		# short posts and replies
	blog/<tid>.md			# long posts with title
	links/<tid>.md		# link recommendations
	likes/<tid>.md		# one target each
	feeds/<tid>				# RSS feeds you recommend
	

`porch` is the command line tool (`porch post`, `porch like`, `porch react`,
`porch timeline` …) and `porch tui` is a keyboard-driven front end over the same
files. It's written in [cljc](../cljc); the TUI needs cljc's `cljc/raw-mode*`,
`cljc/read-key*` and `cljc/term-size*` natives, and deliberately not ncurses:
that would only be reachable through the FFI, which compiles and `dlopen`s a
shared object from `/tmp` at runtime — a real hazard on a multi-user box.

Where a `<tid>` is a TID: a 13-character string encoding microseconds since the
epoch plus a small clock ID, in base32 alphabet chosen so that the alphabetical
order is chronological. This way `ls` gives you the timeline for free and the
TID is the creation time, no separate `createdAt` field is needed.  `porch tid` prints
a new one.

The file format is markdown with a YAML front matter, the front matter is
between `---` lines. The fence is the only way to carry metadata: a file with
no `---` fence is all body, and we never sniff a bare file to see whether it
happens to parse as YAML. That costs `likes/` and `feeds/` a fence they would
otherwise not need, and buys a rule with no ambiguous cases — a post reading
`Reading: notes on Simmel` is a post, not a `Reading:` field — so parse and
render round-trip exactly. A fence that never closes, or one whose contents
aren't a YAML map, is a post that opens with a horizontal rule; nothing is
ever silently dropped.

## Schemas

 * `posts/` The body is the post, top level posts have no front matter at all. A reply has a `reply: {root: <addr>, parent: <addr>}`, mentions `@sam` and tags `#cnc` are parsed from the body, not declared. Optional `edited:` timestamp
 * `blog/` `title:` required: body is the article. Separate from posts so can be collapsed
 * `links/` `url:` required, `title:` optional (cli can fetch it), body is why you're recommending it
 * `likes/` `subject: <addr>` (fenced, no body) any collection can be liked, including someone's link or feed
 * `reactions/` `subject: <addr>`, `emoji: 🔥` (fenced, no body). An emoji reaction to any address; several different emoji per subject are fine, the same one twice is one file.
 * `feeds/` - `url:` plus optional `title:` (fenced, no body). This publishes "I read this feed", A shared fetcher caches the actual entires under `/var/cache/porch/` never inside anyones `.porch` so the fetcher needs no write access to home directories.
 * `profile` - `name:`, optional `links:`, body is bio.

Addresses are `user/colection/rkey`, no file extension. we therefore have to resolve text collections to `.md` and reference collections are bare.

Deletion is `rm`, unlike is `rm` too. Might be dangling references everwhere.


