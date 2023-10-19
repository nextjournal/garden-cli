# nextjournal/garden-template

## Usage

This is a template project for use with [deps-new](https://github.com/seancorfield/deps-new).
It will create a minimal [clerk](https://clerk.vision) project:

    $ clojure -Sdeps '{:deps {io.github.nextjournal/garden-template {:git/sha "<replace-me-with-a-valid-sha>"}}}' -Tnew create :template nextjournal/garden-template :name myusername/mycoollib

Assuming you have installed `deps-new` as your `new` "tool" via:

```bash
clojure -Ttools install io.github.seancorfield/deps-new '{:git/tag "v0.5.2"}' :as new
```

Run this template project's tests (by default, this just validates your template's `template.edn`
file -- that it is valid EDN and it satisfies the `deps-new` Spec for template files):

    $ clojure -T:build test
