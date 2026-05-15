package mx.edu.utez.gibbor.domain.port.input

import mx.edu.utez.gibbor.domain.model.User

interface AuthenticateUserUseCase {
    fun generateOtp(email: String): String
    fun authenticate(email: String): User?
}
