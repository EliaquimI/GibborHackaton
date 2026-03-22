GIBBOR — La Presentación

El problema
Cuando ocurre un delito, la evidencia se pierde, se altera o se desestima. Una víctima graba un video, pero ¿cómo se demuestra que no fue editado? ¿Cómo se demuestra que fue grabado en ese momento exacto y en ese lugar exacto? Hoy en día no se puede. Los tribunales exigen una cadena de custodia que la mayoría de la evidencia digital simplemente no puede proporcionar.

La solución: evidencia inmutable en una blockchain pública
GIBBOR es un botón de pánico. En el momento en que alguien lo activa:

Sus coordenadas GPS y la marca de tiempo se procesan mediante hash criptográfico y se escriben en la blockchain de Stellar a través de un contrato inteligente, de forma permanente, en menos de 30 segundos y por fracciones de centavo.
El teléfono comienza a grabar audio y video automáticamente, sin que la persona, posiblemente en peligro, tenga que hacer nada más.
Cuando la grabación se detiene, la huella digital SHA-256 de ese archivo se ancla en la blockchain. El archivo permanece en el dispositivo; solo su huella matemática se envía a la blockchain.

Por qué existe cada tecnología

Stellar + contrato inteligente en Soroban
Stellar es una blockchain pública y sin permisos que finaliza transacciones en 3 a 5 segundos. El contrato inteligente almacena: quién activó el incidente, cuándo, dónde y el hash de la evidencia. Nadie, ni siquiera GIBBOR, puede alterar o eliminar ese registro. Es una marca de tiempo pública con peso legal.

GoldSky
Los datos crudos de blockchain son difíciles de consultar. GoldSky indexa en tiempo real los eventos de los contratos inteligentes de Stellar y los expone como una API consultable con SQL. Esto significa que las fuerzas del orden, abogados o un juez pueden acceder a todos los incidentes vinculados a un usuario, verificar las marcas de tiempo on-chain y cruzarlas con los hashes de evidencia, sin necesidad de entender blockchain.

Hashing SHA-256
El archivo de video nunca sale del teléfono, a menos que el usuario decida compartirlo. Lo que se registra on-chain es una huella digital de 64 caracteres. Si después alguien afirma que el video fue editado, se ejecuta la misma función SHA-256 sobre el archivo y se compara. Si el hash coincide con el de la blockchain, se demuestra que el archivo no fue alterado. Si no coincide, se prueba la manipulación.

Crossmint
Normalmente, interactuar con una blockchain requiere que el usuario gestione una wallet cripto, llaves privadas y comisiones de gas, una barrera que frena la adopción. Crossmint abstrae todo eso. El usuario inicia sesión con su correo institucional. Crossmint crea y administra una wallet de Stellar de forma invisible. El usuario nunca ve una frase semilla ni necesita comprar criptomonedas. La experiencia es indistinguible de la de una app normal.

La propuesta de valor

Lo que existe hoy	Lo que agrega GIBBOR
Video en un teléfono	Video + prueba inmutable de que fue grabado en X lugar y a Y hora
Testimonio de testigos	Marca de tiempo en blockchain que no puede ser disputada ni eliminada
Respaldo en la nube (alterable)	Hash on-chain, matemáticamente evidente ante manipulaciones
Wallets cripto (complejas)	Acceso con un clic mediante correo institucional
Datos crudos de blockchain (ilegibles)	Registro de incidentes consultable mediante GoldSky

¿Para quién es esto?
Para campus universitarios, programas municipales de seguridad, organizaciones periodísticas, ONG de derechos humanos y cualquier contexto en el que una persona en peligro necesite crear evidencia legalmente creíble e imposible de alterar al instante, sin conocimientos técnicos y sin tener que pensar demasiado.

El botón de pánico es un solo toque. Todo lo demás es automático. La blockchain se encarga del resto.

Si quieres, también te lo puedo traducir a un español más formal para exposición o a uno más simple para diapositivas.