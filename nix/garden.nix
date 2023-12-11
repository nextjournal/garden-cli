{
  rev,
  writeShellScriptBin,
  babashka,
  ...
}:
writeShellScriptBin "garden"
''
  exec ${babashka}/bin/bb --config ${../bb.edn} --deps-root ${../.} -Dnextjournal.garden.rev=${rev} -m nextjournal.garden-cli -- $@
''
