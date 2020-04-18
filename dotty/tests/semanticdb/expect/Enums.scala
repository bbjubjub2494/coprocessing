object Enums:
  import <:<._

  enum Colour:
    import Colour.Red
    case Red, Green, Blue

  enum Directions:
    case North, East, South, West

  enum Suits derives Eql:
    case Hearts, Spades, Clubs, Diamonds

  object Suits:
    def (suit: Suits).isRed: Boolean =
      suit == Hearts || suit == Diamonds

    def (suit: Suits).isBlack: Boolean = suit match
      case Spades | Diamonds => true
      case _                 => false

  enum WeekDays:
    case Monday
    case Tuesday
    case Wednesday
    case Thursday
    case Friday
    case Saturday
    case Sunday

  enum Coin(value: Int):
    case Penny    extends Coin(1)
    case Nickel   extends Coin(5)
    case Dime     extends Coin(10)
    case Quarter  extends Coin(25)
    case Dollar   extends Coin(100)

  enum Maybe[+A]:
    case Just(value: A)
    case None

  enum Tag[A]:
    case IntTag extends Tag[Int]
    case BooleanTag extends Tag[Boolean]

  enum <:<[-A, B]:
    case Refl[C]() extends (C <:< C)

  object <:< :
    given [T] as (T <:< T) = Refl()

  def [A, B](opt: Option[A]) unwrap(using ev: A <:< Option[B]): Option[B] = ev match
    case Refl() => opt.flatMap(identity[Option[B]])

  val some1 = Some(Some(1)).unwrap

  enum Planet(mass: Double, radius: Double) extends java.lang.Enum[Planet]:
    private final val G = 6.67300E-11
    def surfaceGravity = G * mass / (radius * radius)
    def surfaceWeight(otherMass: Double) = otherMass * surfaceGravity

    case Mercury extends Planet(3.303e+23, 2.4397e6)
    case Venus   extends Planet(4.869e+24, 6.0518e6)
    case Earth   extends Planet(5.976e+24, 6.37814e6)
    case Mars    extends Planet(6.421e+23, 3.3972e6)
    case Jupiter extends Planet(1.9e+27,   7.1492e7)
    case Saturn  extends Planet(5.688e+26, 6.0268e7)
    case Uranus  extends Planet(8.686e+25, 2.5559e7)
    case Neptune extends Planet(1.024e+26, 2.4746e7)
