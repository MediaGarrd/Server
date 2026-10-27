# dry4java

dry4java finds candidate duplicate Java code across files and directories. It reports fuzzy structural matches by filename and line range so another mechanism can evaluate and reduce duplication.

## Overview

dry4java parses Java source with JavaParser, selects Java declarations as comparison candidates, normalizes each candidate's AST, and compares sets of structural fingerprints with Jaccard similarity:

```text
score = shared fingerprints / all fingerprints seen in either candidate
```

Names and literal values normalize away, while Java syntax shape remains. Classes, interfaces, records, enums, annotation declarations, methods, constructors, fields, enum constants, initializer blocks, lambdas, expressions, statements, modifiers, and operators all contribute structural nodes.

## Usage

```bash
mvn -q -DskipTests package
java -jar target/dry4java-0.1.0-SNAPSHOT.jar [options] [file-or-directory ...]
```

Options:

```text
--threshold N   Minimum structural similarity score, default 0.82
--min-lines N   Minimum source lines in a candidate declaration, default 4
--min-nodes N   Minimum normalized syntax nodes, default 20
--format F      text or edn, default text
--edn           Same as --format edn
--text          Same as --format text
```

When no paths are provided, dry4java scans `src`. Directory arguments recursively include `.java` files.

Default text output:

```text
DUPLICATE score=0.89
  src/main/java/app/Invoice.java:12-25
  src/main/java/app/Receipt.java:30-44
```

EDN output:

```clojure
{:candidates
 [{:score 0.8909090909090909
   :left {:file "src/main/java/app/Invoice.java", :start-line 12, :end-line 25}
   :right {:file "src/main/java/app/Receipt.java", :start-line 30, :end-line 44}
   :left-nodes 88
   :right-nodes 91}]}
```

## Development

```bash
mvn test
```
