{
  rev,
  writeShellScriptBin,
  babashka,
  ...
}:
writeShellScriptBin "garden"
''
  exec ${babashka}/bin/bb --deps-root ${../.} -Dnextjournal.garden.rev=${rev} -m nextjournal.garden-cli -- $@
''
