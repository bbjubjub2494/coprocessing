{ jre, lib, sbt }:

sbt.mkDerivation rec {
  pname = "coprocessing";
  version = "0.0.1";

  depsSha256 = "sha256-3N38JEcdWC6Eaq99egNYu6l+IamJyJrT5goAxBCGjos=";

  depsWarmupCommand = ''
    sbt doc
  '';

  src = ./.;

  buildPhase = ''
    sbt package
  '';

  installPhase = ''
    mkdir -p $out
    cp -a target/*/*.jar -t $out
  '';
}

