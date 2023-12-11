{
  description = "CLI for application.garden";
  inputs.nixpkgs.url = "nixpkgs/nixpkgs-unstable";
  outputs = {
    self,
    nixpkgs,
    ...
  }: let
    supportedSystems = [
      "x86_64-linux"
      "aarch64-linux"
      "x86_64-darwin"
      "aarch64-darwin"
    ];
    forAllSystems = nixpkgs.lib.genAttrs supportedSystems;
  in {
    packages = forAllSystems (
      system: let
        pkgs = import nixpkgs {
          inherit system;
          overlays = [self.overlays.default];
        };
      in {
        inherit (pkgs) garden;
        default = pkgs.garden;
      }
    );
    apps = forAllSystems (
      system: {
        garden = {
          type = "app";
          program = "${self.packages.system.garden}/bin/garden";
        };
      }
    );
    overlays.default = final: prev: {
      garden = final.callPackage ./nix/garden.nix {rev = self.shortRev or "undefined";};
    };
  };
}
