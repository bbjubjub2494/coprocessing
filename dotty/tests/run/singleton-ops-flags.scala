package example {

  import compiletime.S
  import compiletime.ops.int.<<

  object TastyFlags:

    final val EmptyFlags  = baseFlags
    final val Erased      = EmptyFlags.next
    final val Internal    = Erased.next
    final val Inline      = Internal.next
    final val InlineProxy = Inline.next
    final val Opaque      = InlineProxy.next
    final val Scala2x     = Opaque.next
    final val Extension   = Scala2x.next
    final val Given       = Extension.next
    final val Exported    = Given.next
    final val NoInits     = Exported.next
    final val TastyMacro  = NoInits.next
    final val Enum        = TastyMacro.next
    final val Open        = Enum.next

    type LastFlag = Open.idx.type

    def (s: FlagSet).debug: String =
      if s == EmptyFlags then "EmptyFlags"
      else s.toSingletonSets[LastFlag].map ( [n <: Int] => (flag: SingletonFlagSet[n]) => flag match {
        case Erased      => "Erased"
        case Internal    => "Internal"
        case Inline      => "Inline"
        case InlineProxy => "InlineProxy"
        case Opaque      => "Opaque"
        case Scala2x     => "Scala2x"
        case Extension   => "Extension"
        case Given       => "Given"
        case Exported    => "Exported"
        case NoInits     => "NoInits"
        case TastyMacro  => "TastyMacro"
        case Enum        => "Enum"
        case Open        => "Open"
      }) mkString(" | ")

    object opaques:

      opaque type FlagSet = Int
      opaque type EmptyFlagSet <: FlagSet = 0
      opaque type SingletonFlagSet[N <: Int] <: FlagSet = 1 << N

      opaque type SingletonSets[N <: Int] = Int

      private def [N <: Int](n: N).shift: 1 << N = ( 1 << n ).asInstanceOf
      private def [N <: Int](n: N).succ : S[N]   = ( n +  1 ).asInstanceOf

      final val baseFlags: EmptyFlagSet = 0

      def (s: EmptyFlagSet).next: SingletonFlagSet[0] = 1
      def [N <: Int: ValueOf](s: SingletonFlagSet[N]).next: SingletonFlagSet[S[N]] = valueOf[N].succ.shift
      def [N <: Int: ValueOf](s: SingletonFlagSet[N]).idx: N = valueOf[N]
      def [N <: Int](s: FlagSet).toSingletonSets: SingletonSets[N] = s
      def (s: FlagSet) | (t: FlagSet): FlagSet = s | t

      def [A, N <: Int: ValueOf](ss: SingletonSets[N]).map(f: [t <: Int] => (s: SingletonFlagSet[t]) => A): List[A] =
        val maxFlag = valueOf[N]
        val buf = List.newBuilder[A]
        var current = 0
        while (current <= maxFlag) {
          val flag = current.shift
          if ((flag & ss) != 0) {
            buf += f(flag)
          }
          current += 1
        }
        buf.result

    end opaques

    export opaques._

}


import example.TastyFlags._

@main def Test = assert((Open | Given | Inline | Erased).debug == "Erased | Inline | Given | Open")
