def call(Map config) {
    pipeline {
        agent none

        environment {
			ENVIRONMENT = "${env.BRANCH_NAME}"
            PORT = "${env.BRANCH_NAME == 'main' ? '3000' : env.BRANCH_NAME == 'dev' ? '3001' : '5000'}"
		}
		stages {
            stage('Build and test'){
                agent {
                    docker {
                        image 'node:alpine3.20'
                    }
                }
                stages {
                    stage('Build'){
                        steps {
                            script {
                                echo "Build started..."
                                sh 'chmod +x ./scripts/build.sh'
                                sh '. ./scripts/build.sh'
                            }
                        }
                    }
                    stage('Test'){
                        steps {
                            script {
                                echo "Test started..."
                                sh 'chmod +x ./scripts/test.sh'
                                sh '. ./scripts/test.sh'
                            }
                        }
                    }
                }
            }
			stage('Dockerfile lint'){
                agent {
                    docker {
                        image "$config.dockerAgent"
                    }
                }
                steps {
                    sh "hadolint -f json Dockerfile > $config.hadolintOutputFile"
                }
                post {
                    failure {
                        sh "cat $config.hadolintOutputFile"
                        archiveArtifacts artifacts: "$config.hadolintOutputFile", fingerprint: true
                    }
                }
            }
            stage('Docker build and push'){
                agent {
                    docker {
                        image 'lucascx/trivy-docker:v1.0'
                        args '-v /var/run/docker.sock:/var/run/docker.sock --user 1000:999'
                    }
                }
                environment {
                    HOME = "${env.WORKSPACE}"
                }
                stages{
                    stage('Build'){
                        steps {
                            script {
                                sh "docker build -t $config.buildImageName ."
                            }
                        }
                    }
                    stage('Trivy scan'){
                        when {
                            expression { "$config.runTrivy" == 'true'}
                        }
                        steps {
                            script {
                                sh "trivy image --output $config.trivyOutputFile $config.buildImageName"
                                archiveArtifacts artifacts: "$config.trivyOutputFile", allowEmptyArchive: true
                            }
                        }
                    }
                    stage('Push to dockerhub'){
                        steps {
                            script {
                                sh "docker tag $config.buildImageName $config.dockerUser/$config.buildImageName"
                                withDockerRegistry([credentialsId: "$config.dockerCredentials", url: '']) {
                                    sh "docker push $config.dockerUser/$config.buildImageName"
                                }
                            }
                        }
                    }
                }
            }
            stage('Docker pull and deploy'){
				agent {
					docker {
						image "$config.dockerImageName"
						args '-v /var/run/docker.sock:/var/run/docker.sock --user 1000:999'
					}
				}
                environment {
                    HOME = "${env.WORKSPACE}"
                }
				stages {
					stage('Pull'){
						steps {
							script {
                                echo "TEST"
                                echo "$config.dockerUser"
                                sh "echo $config.dockerUser/$config.buildImageName"
								sh "docker pull $config.dockerUser/$config.buildImageName"
							}
						}
					}
					stage('Deploy'){
						steps {
							script {
								sh "docker ps -aq --filter label=env=$ENVIRONMENT | xargs -r docker rm -f"
								sh "docker run -d --label env=$ENVIRONMENT --expose $PORT -p $PORT:3000 $config.dockerUser/$config.buildImageName"
							}
						}
					}
				}
			}
        }
	}
}


