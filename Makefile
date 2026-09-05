# atilde — the `porch` command line tool.
#
# Development doesn't need any of this: deps.edn puts src/ on cljc's load path,
# so `./bin/porch ...` and `make test` run straight from source. The build target
# is for handing a single file to a machine that has no cljc installed.

PREFIX ?= $(HOME)/.local
BINDIR  = $(PREFIX)/bin
CLJC   ?= cljc
# bundle.clj ships in cljc's share dir; `cljc doctor` reports where that is.
SHAREDIR = $(shell $(CLJC) doctor 2>/dev/null | sed -n 's/^built sharedir: *//p')
BUNDLE   = $(SHAREDIR)/bundle.clj

SRC = src/porch.clj $(wildcard src/porch/*.clj)

.PHONY: all test run clean install uninstall
all: porch

# bundle.clj embeds porch.clj plus every .clj it transitively requires — our
# namespaces, babashka.cli, clj-yaml, clojure.string — beside the interpreter
# and compiles the lot. ~410K, links only libc and libm.
porch: $(SRC) deps.edn
	$(CLJC) $(BUNDLE) src/porch.clj $@

# Static: no glibc version to match, which is what you want when the tilde box
# isn't the box you built on.
porch-static: $(SRC) deps.edn
	$(CLJC) $(BUNDLE) --static src/porch.clj $@

test:
	$(CLJC) test porch_test.clj

# Exercise the real commands against a throwaway $HOME, so a `make check` can
# never scribble on your actual porch.
check: porch
	@tmp=$$(mktemp -d); \
	 HOME=$$tmp PORCH_USER=$$USER ./porch post "smoke test" >/dev/null; \
	 HOME=$$tmp PORCH_USER=$$USER ./porch ls; \
	 rm -rf $$tmp

install: porch
	install -d $(BINDIR)
	install -m 755 porch $(BINDIR)/porch

uninstall:
	rm -f $(BINDIR)/porch

clean:
	rm -f porch porch-static
