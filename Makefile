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

.PHONY: all test test-bb test-jolt run clean install uninstall
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

# Same suite on Babashka, through the bb/cljc.clj shim; see bb/test_runner.clj
# for what it has to paper over.
test-bb:
	bb bb/test_runner.clj

test-jolt:
	jolt -A:jolt run bb/test_runner.clj

# A jolt binary. ~28M and still needs $HOME/.jolt for a compile cache on first
# run, because porch.clj exits at load time and so can only be required from
# inside -main; see jolt/porch/jolt_main.clj.
porch-jolt: $(SRC) deps.edn bb/cljc.clj jolt/porch/jolt_main.clj
	jolt -A:jolt build -m porch.jolt-main -o $@

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
	rm -rf porch porch-static porch-jolt porch-jolt.build
