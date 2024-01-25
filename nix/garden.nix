{
  rev,
  writeShellScriptBin,
  babashka,
  ...
}:
writeShellScriptBin "garden"
''
  exec ${babashka}/bin/bb -Sforce --config ${../bb.edn} --deps-root ${../.} -Dnextjournal.garden.rev=${rev} -m nextjournal.garden-cli -- $@
''
