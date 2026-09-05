# atilde — the `wall` command line tool.
#
# Development doesn't need any of this: deps.edn puts src/ on cljc's load path,
# so `./bin/wall ...` and `make test` run straight from source. The build target
# is for handing a single file to a machine that has no cljc installed.

PREFIX ?= $(HOME)/.local
BINDIR  = $(PREFIX)/bin
CLJC   ?= cljc
# bundle.clj ships in cljc's share dir; `cljc doctor` reports where that is.
SHAREDIR = $(shell $(CLJC) doctor 2>/dev/null | sed -n 's/^built sharedir: *//p')
BUNDLE   = $(SHAREDIR)/bundle.clj

SRC = src/wall.clj $(wildcard src/wall/*.clj)

.PHONY: all test run clean install uninstall
all: wall

# bundle.clj embeds wall.clj plus every .clj it transitively requires — our
# namespaces, babashka.cli, clj-yaml, clojure.string — beside the interpreter
# and compiles the lot. ~410K, links only libc and libm.
wall: $(SRC) deps.edn
	$(CLJC) $(BUNDLE) src/wall.clj $@

# Static: no glibc version to match, which is what you want when the tilde box
# isn't the box you built on.
wall-static: $(SRC) deps.edn
	$(CLJC) $(BUNDLE) --static src/wall.clj $@

test:
	$(CLJC) test wall_test.clj

# Exercise the real commands against a throwaway $HOME, so a `make check` can
# never scribble on your actual wall.
check: wall
	@tmp=$$(mktemp -d); \
	 HOME=$$tmp WALL_USER=$$USER ./wall post "smoke test" >/dev/null; \
	 HOME=$$tmp WALL_USER=$$USER ./wall ls; \
	 rm -rf $$tmp

install: wall
	install -d $(BINDIR)
	install -m 755 wall $(BINDIR)/wall

uninstall:
	rm -f $(BINDIR)/wall

clean:
	rm -f wall wall-static
