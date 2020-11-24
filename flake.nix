{
  description = "Coprocessing";

  inputs = {
    sbt-derivation.url = "github:zaninime/sbt-derivation";
    utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, sbt-derivation, utils }:
    let
      jdk = "jdk11_headless";
    in
    utils.lib.simpleFlake {
      inherit self nixpkgs;
      name = "coprocessing";
      shell = { pkgs }: pkgs.mkShell {
        buildInputs = [
          pkgs.coursier
          pkgs.${jdk}
          pkgs.sbt
	];
      };
      preOverlays = [ sbt-derivation.overlay ];
      overlay = (final: prev: {
	coprocessing = {
	  defaultPackage = final.callPackage ./. {
	    jre = final.${jdk};
	  };
	};
	sbt = prev.sbt.override {
	  jre = final.${jdk};
	};
      });
    };
}
