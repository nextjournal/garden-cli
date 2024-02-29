# garden-template

A template for [application.garden](https://application.garden) projects.

## Usage

This gets automatically used by `garden init`.

If you want you can also use it with [deps-new](https://github.com/seancorfield/deps-new).
It will produce a new library project when run:

    $ clojure -Sdeps '{:deps {io.github.nextjournal/garden-template {:local/root "."}}}' -Tnew create :template garden/template :name myusername/mycoolproject

Assuming you have installed `deps-new` as your `new` "tool" via:

```bash
clojure -Ttools install-latest :lib io.github.seancorfield/deps-new :as new
```

## License

Copyright © 2024 Nextjournal
