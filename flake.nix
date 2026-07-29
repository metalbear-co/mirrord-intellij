{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
  };

  outputs =
    inputs:
    let
      inherit (inputs.nixpkgs) lib;
    in
    {
      devShells = lib.genAttrs lib.systems.flakeExposed (
        system:
        let
          pkgs = import inputs.nixpkgs {
            inherit system;
            config.allowUnfree = true;
          };
        in
        {
          default = pkgs.mkShell {
            packages = with pkgs; [
              jdk21
              jetbrains.idea
            ];
          };
        }
      );
    };
}
