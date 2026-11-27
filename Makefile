.PHONY: test lint-test full-test

JF=./gradlew
JF_ARGS=test --warning-mode all

SHELL=bash

test:
	$(JF) $(JF_ARGS)

lint-test:
	cd vendor/dry4java && mvn -q -DskipTests package
	./scripts/dry_test.sh
	@echo "Passable amount of duplicate code"

full-test: lint-test test
