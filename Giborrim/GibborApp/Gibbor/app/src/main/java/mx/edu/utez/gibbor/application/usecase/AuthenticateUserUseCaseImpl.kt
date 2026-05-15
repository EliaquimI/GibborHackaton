package mx.edu.utez.gibbor.application.usecase

import mx.edu.utez.gibbor.domain.model.User
import mx.edu.utez.gibbor.domain.port.input.AuthenticateUserUseCase

class AuthenticateUserUseCaseImpl : AuthenticateUserUseCase {

    override fun generateOtp(email: String): String {
        return (100000..999999).random().toString()
    }

    override fun authenticate(email: String): User? {
        val norm = email.trim()
            .replace("[^a-zA-Z0-9@._-]".toRegex(), "")
            .lowercase()
        return if (norm.isNotBlank()) User(email = norm) else null
    }
}
